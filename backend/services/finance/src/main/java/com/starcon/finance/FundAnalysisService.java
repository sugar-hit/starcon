package com.starcon.finance;

import static com.starcon.finance.FundAnalysisModels.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 12.7.2.1 资金分析。
 *
 * <p>蓝图 12-finance.md：「资金分析：现金流入流出趋势、各币种资金分布与汇率敞口、资金周转天数、付款集中度与流动性预警。」输入：「资金流水；应收应付账期；汇率数据。」输出：「资金报表 + 汇率敞口测算 + 流动性预警。」
 *
 * <p><b>本模块的六项能力，各自的真数据源</b>：
 *
 * <ol>
 *   <li><b>现金流入流出趋势</b> —— 实际流入取 12.2.1.1 的 {@code finance_receipt}
 *       （按 {@code received_at} 的 UTC 日历日分桶）；预期流入取 12.1.1.1 的
 *       {@code finance_receivable} 未核销余额（按 {@code due_date}）；计划流出取 12.1.2.2 的
 *       {@code finance_payment_plan}（按 {@code planned_paid_date}）。</li>
 *   <li><b>各币种资金分布</b> —— 收款 / 应收 / 应付 / 计划四路的真实聚合，全部按币种分开。</li>
 *   <li><b>汇率敞口测算</b> —— 资产端 = 未核销应收，负债端 = 未付应付；
 *       汇率经 {@link CatalogExchangeRatePort} 从 catalog 服务读（跨服务只走授权 HTTP API）。</li>
 *   <li><b>资金周转天数</b> —— DSO / DPO 由真实聚合算出，分子分母都是同一币种、同一日历日口径。</li>
 *   <li><b>付款集中度</b> —— 按计划付款日分桶 + 按付款对象分组，取真实占比。</li>
 *   <li><b>流动性预警</b> —— 四条规则跑在真实聚合上，阈值由租户自己维护（没配过时用蓝图默认档）。</li>
 * </ol>
 *
 * <p><b>五条口径纪律</b>：
 *
 * <ol>
 *   <li><b>只读上游。</b>本模块只写自己的两张表（{@code finance_liquidity_threshold}、
 *       {@code finance_fund_exposure_snapshot}），上游六张表（收款 / 应收 / 应付 / 付款计划）
 *       一个字节都不往回写。</li>
 *   <li><b>实际 ≠ 计划。</b>12.1.2.2 的付款计划写不出「已付款」（{@code execution_available}
 *       被 CHECK 钉死为 false），12.2.2.1 未交付 ⇒ 流出侧只有<b>计划口径</b>与<b>累计已付口径</b>。
 *       两者都**不**被当成「实际流出的时间序列」。</li>
 *   <li><b>日历日一律 UTC。</b>所有分桶、窗口、天数都按 UTC 日历日
 *       （{@code LocalDate.now(ZoneOffset.UTC)}；SQL 侧 {@code (col at time zone 'UTC')::date}）。
 *       用会话时区的话，换一条连接就换一套分桶，分布会凭空多出空桶。</li>
 *   <li><b>币种分开。</b>USD 与 EUR 加起来不是任何一个金额 ⇒ 没有任何一处跨币种求和。</li>
 *   <li><b>缺口如实标注。</b>12.7.1.1 / 12.7.1.2 未交付 ⇒ 账户级余额与银行流水不可用，
 *       归属模块写在 DB 的 CHECK 里、能力声明里、响应里，绝不用常量冒充。</li>
 * </ol>
 */
@Service
public class FundAnalysisService {

  /** 默认区间长度（自然日，含首尾）。 */
  static final int DEFAULT_RANGE_DAYS = 30;

  /** 区间上限（自然日）—— 防止一个参数把服务拖死。 */
  static final int MAX_RANGE_DAYS = 400;

  /** 一页最大条数。 */
  static final int MAX_PAGE_SIZE = 200;

  /** 蓝图给定的四档流动性阈值（租户没配过时生效的口径）。 */
  static final List<Threshold> BLUEPRINT_THRESHOLDS =
      List.of(
          new Threshold(
              FinanceFundLiquidityRule.PAYMENT_CONCENTRATION, "付款集中度", new BigDecimal("0.40"), "单期计划付款额占窗口总额的比例上限（0.40 = 40%）"),
          new Threshold(
              FinanceFundLiquidityRule.NET_OUTFLOW_GAP, "净流出缺口", new BigDecimal("1.00"), "计划流出 ÷ 流入 的上限（1.00 = 流出与流入持平即预警）"),
          new Threshold(
              FinanceFundLiquidityRule.RECEIVABLE_OVERDUE, "应收逾期", new BigDecimal("0.30"), "逾期未核销应收 ÷ 未核销应收 的比例上限（0.30 = 30%）"),
          new Threshold(
              FinanceFundLiquidityRule.CASH_BUFFER_DAYS, "现金覆盖天数", new BigDecimal("7"), "窗口内实际流入按日均计划流出能支撑的天数下限"));

  /** 实际流出没有时间序列的原因（写进响应，别让人以为漏算了）。 */
  static final String ACTUAL_OUTFLOW_BASIS =
      "实际付款没有日期维度：12.2.2.1 付款单与审批未交付，"
          + "finance_payable_line.paid_amount 当前恒为 0，且付款行上没有付款时刻字段。"
          + "因此流出侧只有「计划流出（按计划付款日）」与「累计已付（无日期）」两个口径，"
          + "本模块不把它们当成实际流出的时间序列。";

  /** 实际流出的归属模块。 */
  static final String ACTUAL_OUTFLOW_OWNER = "12.2.2.1";

  /** 内部形态的一条阈值。 */
  record Threshold(FinanceFundLiquidityRule rule, String label, BigDecimal value, String note) {}

  /** 内部形态：窗口内一个币种的三路资金。 */
  record WindowFlow(
      String currency,
      BigDecimal actualInflow,
      BigDecimal expectedInflow,
      BigDecimal plannedOutflow) {}

  /** 内部形态：一个时间桶的金额。 */
  record BucketAmount(LocalDate period, BigDecimal amount) {}

  /** 内部形态：趋势单元（金额 + 笔数）。 */
  record Cell(BigDecimal amount, long count) {}

  private final DSLContext sql;
  private final CatalogExchangeRatePort rates;

  public FundAnalysisService(DSLContext sql, CatalogExchangeRatePort rates) {
    this.sql = sql;
    this.rates = rates;
  }

  // ================================================================ 能力声明

  /**
   * 能力声明：**每一维说清「有没有、依据是什么、缺了会怎样」**。
   *
   * <p>⚠️ 必须把「已交付但本模块没接」与「未交付」分开写：
   * 全部写成「不可用」的话，运维会以为要去催 12.7.1，而实际上 12.7.2.1 的六项能力
   * **全部已交付**——不可用的只是**账户级**与**银行流水级**的那两个口径。
   */
  public FinanceFundAnalysisCapabilities capabilities() {
    var capabilities = new ArrayList<FinanceFundAnalysisCapability>();
    var gaps = new ArrayList<FinanceFundAnalysisDownstreamGap>();

    capabilities.add(
        new FinanceFundAnalysisCapability(
            "12.7.2.1",
            "现金流入流出趋势",
            true,
            "finance_receipt + finance_receivable + finance_payment_plan（只读）",
            "",
            "三个口径并列，不混：实际流入（已到账收款，按到账时刻）、"
                + "预期流入（未核销应收，按到期日）、计划流出（付款计划，按计划付款日）。"
                + "净額只给「预期流入 − 计划流出」这一种同口径预测净额 —— "
                + "**不**给「实际净额」：实际流出当前没有日期维度，硬凑等于把缺失当零。"));

    capabilities.add(
        new FinanceFundAnalysisCapability(
            "12.7.2.1",
            "各币种资金分布",
            true,
            "finance_receipt / finance_receivable / finance_payable / finance_payment_plan（只读）",
            "",
            "按币种分组的真实聚合：累计已到账现金、其中未核销、其中预收、未核销应收、"
                + "未付应付、累计已付、计划流出、未来结算净额。"
                + "⚠️ 这七个数是**七种不同口径的度量**，不是资产负债表；"
                + "「计划流出」与「未付应付」**重叠**，不许相加。"));

    capabilities.add(
        new FinanceFundAnalysisCapability(
            "12.7.2.1",
            "汇率敞口测算",
            true,
            "finance_receivable + finance_payable（只读）+ catalog 5.2.2.1 汇率（授权 HTTP API）",
            "",
            "资产端 = 未核销应收，负债端 = 未付应付，净敞口 = 资产端 − 负债端；"
                + "折算成本位币并给出 ±1% / ±5% 的敏感度与敞口集中度。"
                + "汇率取不到一律 503 显式失败，**绝不**返回空集合冒充「没有敞口」。"
                + "测算可落成快照（finance_fund_exposure_snapshot），把汇率与生效日一起钉住，便于复盘。"));

    capabilities.add(
        new FinanceFundAnalysisCapability(
            "12.7.2.1",
            "资金周转天数",
            true,
            "finance_receivable + finance_receipt + finance_payable（只读）",
            "",
            "DSO = 期末未核销应收 ÷ 期间实际现金流入 × 期间天数；"
                + "DPO = 期末未付应付 ÷ 期间应付发生额 × 期间天数；"
                + "资金周转天数 = DSO − DPO。分子分母**同币种、同 UTC 日历日口径**，"
                + "分母为 0 时给 null（「算不出」≠「0 天」）。"));

    capabilities.add(
        new FinanceFundAnalysisCapability(
            "12.7.2.1",
            "付款集中度",
            true,
            "finance_payment_plan + finance_payable（只读）",
            "",
            "窗口内计划付款额按 UTC 日历分桶（按日 / 周 / 月）算时间集中度，"
                + "按付款对象分组算对象集中度。"
                + "口径是**计划付款**：12.1.2.2 的计划表里写不出「已付款」，"
                + "所以这里不是「已付集中度」。"));

    capabilities.add(
        new FinanceFundAnalysisCapability(
            "12.7.2.1",
            "流动性预警",
            true,
            "四路真实聚合 + finance_liquidity_threshold（租户可维护）",
            "",
            "四条规则：付款集中度、净流出缺口、应收逾期、现金覆盖天数。"
                + "阈值是**比例或天数**（不是金额 —— 金额阈值必须指定币种），"
                + "租户没配过时生效的是蓝图默认档，响应里标出 source。"));

    capabilities.add(
        new FinanceFundAnalysisCapability(
            "12.7.1.1",
            "资金账户与账户级余额",
            false,
            "上游表不存在：finance 服务没有任何资金账户表（fund_account / bank_account 零命中）",
            "12.7.1.1",
            "⇒ 本模块**不产出账户级余额**，也**不产出账户级的资金分布**，"
                + "只到**币种级**。DB 层用 CHECK 把受管账户归属钉死恒为 NULL，"
                + "归属模块写在数据上（fund_account_owner_module 恒为 '12.7.1.1'）。"
                + "「累计已到账现金」**不等于**「在手账户余额」—— 没有账户表就没有余额，"
                + "本模块绝不拿累计数冒充余额。"));

    capabilities.add(
        new FinanceFundAnalysisCapability(
            "12.7.1.2",
            "银行流水与银行对账",
            false,
            "上游表不存在：没有任何银行流水表（bank_statement 零命中）",
            "12.7.1.2",
            "⇒ 本模块**不做银行对账、不产出对账差异、不产出未达账项**。"
                + "DB 层用 CHECK 把银行流水归属钉死恒为 NULL，"
                + "归属模块写在数据上（bank_statement_owner_module 恒为 '12.7.1.2'）。"
                + "「实际流入」取自 12.2.1.1 的**收款单**（真实到账事实），不是银行流水。"));

    capabilities.add(
        new FinanceFundAnalysisCapability(
            "12.2.2.1",
            "实际付款与付款时刻",
            false,
            "写路径未开放：finance_payable_line.paid_amount 由 12.2.2.1 写，当前恒为 0",
            "12.2.2.1",
            "⇒ 实际流出**没有时间序列**（付款行上没有付款时刻字段）。"
                + "本模块只报「累计已付」（无日期口径）与「计划流出」（有日期、计划口径）。"
                + "12.2.2.1 交付后，本模块的流出侧数字会**自动**跟着变，不需要改任何一列或一条 SQL。"));

    capabilities.add(
        new FinanceFundAnalysisCapability(
            "13.1.1.1",
            "存货周转天数（现金转换周期的存货段）",
            false,
            "存货不在本服务（07 库存核算 / 08 仓储作业）",
            "13.1.1.1",
            "⇒ 资金周转天数 = DSO − DPO，**不含存货段**；"
                + "dioDays 恒为 null（**不用 0 冒充**），现金转换周期 CCC 同样不给。"
                + "蓝图举的「大量外币存货 + 本币负债」里，存货那一半属 13。"));

    gaps.add(
        new FinanceFundAnalysisDownstreamGap(
            "1", "工作台卡片与预警推送", "web", false, "01 工作台与门户",
            "本模块已交付资金报表与流动性预警本身；"
                + "按蓝图「下游 = 1 工作台」，卡片位与推送属卷 01，本模块**不产出**卡片数据。"));

    gaps.add(
        new FinanceFundAnalysisDownstreamGap(
            "13.1.1.1", "利润与经营分析", "analytics", false, "13 利润与经营分析",
            "13 未交付：资金报表是它的一处上游输入；"
                + "存货周转天数（CCC 的存货段）也归它。"));

    gaps.add(
        new FinanceFundAnalysisDownstreamGap(
            "12.7.1.1", "资金账户与银行对账", "finance", false, "12.7 资金",
            "账户级余额与银行流水未交付："
                + "本模块的资金分布只到币种级，流入侧取自收款单而不是银行流水。"));

    return new FinanceFundAnalysisCapabilities(List.copyOf(capabilities), List.copyOf(gaps));
  }

  // ================================================================ 筛选项

  /** 筛选项下拉：全部来自**真实数据**（distinct 查询），不是写死的词表。 */
  public FinanceFundAnalysisOptions options() {
    var today = LocalDate.now(ZoneOffset.UTC);
    var currencies =
        sql.fetch(
                "select currency from finance_receipt where status = 'RECORDED'"
                    + " union select currency from finance_receivable"
                    + " where status in ('OPEN','PARTIALLY_SETTLED')"
                    + " union select currency from finance_payable"
                    + " where status in ('OPEN','PARTIALLY_PAID')"
                    + " union select currency from finance_payment_plan"
                    + " where status in ('PLANNED','SCHEDULED')"
                    + " order by currency")
            .map(r -> r.get(0, String.class));
    var baseCandidates =
        sql.fetch(
                "select base_currency from finance_receivable where base_currency is not null"
                    + " union select base_currency from finance_payable where base_currency is not null"
                    + " order by base_currency")
            .map(r -> r.get(0, String.class));
    return new FinanceFundAnalysisOptions(
        currencies,
        baseCandidates,
        today,
        "默认区间：最近 " + DEFAULT_RANGE_DAYS + " 个自然日（UTC 日历日）",
        "本位币候选取自本租户单据上已记的汇率快照（base_currency），"
            + "不是写死的词表；候选多于一个时必须显式指定 baseCurrency。");
  }

  // ================================================================ 现金流入流出趋势

  /**
   * 现金流入流出趋势：时间桶 × 币种。
   *
   * <p>⚠️ **三个口径并列，不混**：实际流入（已到账）、预期流入（未核销应收）、计划流出（付款计划）。
   */
  public FinanceFundFlowTrend trend(FinanceFundFlowTrendQuery query) {
    var today = LocalDate.now(ZoneOffset.UTC);
    var from = query.fromDate() != null ? query.fromDate() : today.minusDays(DEFAULT_RANGE_DAYS - 1L);
    var to = query.toDate() != null ? query.toDate() : today;
    validateRange(from, to);
    var granularity = query.granularity() != null ? query.granularity() : defaultGranularity(from, to);
    var currency = normalizeCurrency(query.currency());

    // 三路：实际流入（timestamptz）、预期流入（date）、计划流出（date）。
    var actual = flowCells(
        "select "
            + periodExpr(granularity, "r.received_at", true)
            + " as period_start, r.currency, sum(r.amount) as amount, count(*) as cnt"
            + " from finance_receipt r where r.status = 'RECORDED'"
            + " and (r.received_at at time zone 'UTC')::date >= ?::date"
            + " and (r.received_at at time zone 'UTC')::date <= ?::date"
            + currencyClause(currency, "r.currency")
            + " group by 1, 2",
        List.of(from, to),
        currency);
    var expected = flowCells(
        "select "
            + periodExpr(granularity, "r.due_date", false)
            + " as period_start, r.currency,"
            + " sum(r.amount - r.settled_amount) as amount, count(*) as cnt"
            + " from finance_receivable r"
            + " where r.status in ('OPEN','PARTIALLY_SETTLED')"
            + " and r.due_date >= ?::date and r.due_date <= ?::date"
            + currencyClause(currency, "r.currency")
            + " group by 1, 2",
        List.of(from, to),
        currency);
    var planned = flowCells(
        "select "
            + periodExpr(granularity, "p.planned_paid_date", false)
            + " as period_start, p.currency, sum(p.planned_amount) as amount, count(*) as cnt"
            + " from finance_payment_plan p"
            + " where p.status in ('PLANNED','SCHEDULED')"
            + " and p.planned_paid_date >= ?::date and p.planned_paid_date <= ?::date"
            + currencyClause(currency, "p.currency")
            + " group by 1, 2",
        List.of(from, to),
        currency);

    var periods = new TreeSet<LocalDate>();
    var currencies = new TreeSet<String>();
    for (var map : List.of(actual, expected, planned)) {
      map.keySet().forEach(
          key -> {
            periods.add(key.period());
            currencies.add(key.currency());
          });
    }

    var buckets = new ArrayList<FinanceFundFlowBucket>();
    if (!periods.isEmpty()) {
      var sequence = periodSequence(periods.first(), periods.last(), granularity);
      for (var period : sequence) {
        for (var ccy : currencies) {
          var a = actual.getOrDefault(new CellKey(period, ccy), new Cell(BigDecimal.ZERO, 0));
          var e = expected.getOrDefault(new CellKey(period, ccy), new Cell(BigDecimal.ZERO, 0));
          var p = planned.getOrDefault(new CellKey(period, ccy), new Cell(BigDecimal.ZERO, 0));
          buckets.add(
              new FinanceFundFlowBucket(
                  period,
                  ccy,
                  money(a.amount()),
                  money(e.amount()),
                  money(p.amount()),
                  money(e.amount().subtract(p.amount())),
                  a.count(),
                  e.count(),
                  p.count()));
        }
      }
    }

    var totals = new ArrayList<FinanceFundFlowCurrencyTotal>();
    for (var ccy : currencies) {
      var a = sumOf(actual, ccy);
      var e = sumOf(expected, ccy);
      var p = sumOf(planned, ccy);
      totals.add(
          new FinanceFundFlowCurrencyTotal(
              ccy, money(a), money(e), money(p), money(e.subtract(p))));
    }

    return new FinanceFundFlowTrend(
        from,
        to,
        granularity.name(),
        today,
        ACTUAL_OUTFLOW_BASIS,
        ACTUAL_OUTFLOW_OWNER,
        List.copyOf(buckets),
        List.copyOf(totals));
  }

  // ================================================================ 各币种资金分布

  /** 各币种资金分布：四路真实聚合，按币种分开。 */
  public FinanceFundCurrencyDistribution distribution(FinanceFundCurrencyDistributionQuery query) {
    var today = LocalDate.now(ZoneOffset.UTC);
    var currency = normalizeCurrency(query.currency());

    var received =
        fetch(
                "select currency, sum(amount) as amount, sum(unsettled_amount) as unsettled,"
                    + " sum(case when advance_flag then amount else 0 end) as advance, count(*) as cnt"
                    + " from finance_receipt where status = 'RECORDED'"
                    + currencyClause(currency, "currency")
                    + " group by currency order by currency",
                currency)
            .map(
                r ->
                    new Flow(
                        r.get("currency", String.class),
                        r.get("amount", BigDecimal.class),
                        r.get("unsettled", BigDecimal.class),
                        r.get("advance", BigDecimal.class),
                        r.get("cnt", Long.class)));
    var receivables =
        fetch(
                "select currency, sum(amount - settled_amount) as open_amount, count(*) as cnt"
                    + " from finance_receivable where status in ('OPEN','PARTIALLY_SETTLED')"
                    + currencyClause(currency, "currency")
                    + " group by currency order by currency",
                currency)
            .map(
                r ->
                    new Balance(
                        r.get("currency", String.class),
                        r.get("open_amount", BigDecimal.class),
                        r.get("cnt", Long.class)));
    var payables =
        fetch(
                "select currency, sum(unpaid_amount) as unpaid, count(*) as cnt"
                    + " from finance_payable where status in ('OPEN','PARTIALLY_PAID')"
                    + currencyClause(currency, "currency")
                    + " group by currency order by currency",
                currency)
            .map(
                r ->
                    new Balance(
                        r.get("currency", String.class),
                        r.get("unpaid", BigDecimal.class),
                        r.get("cnt", Long.class)));
    var paid =
        fetch(
                "select currency, sum(paid_amount) as paid from finance_payable"
                    + " where paid_amount > 0"
                    + currencyClause(currency, "currency")
                    + " group by currency order by currency",
                currency)
            .map(r -> new Amount(r.get("currency", String.class), r.get("paid", BigDecimal.class)));
    var planned =
        fetch(
                "select currency, sum(planned_amount) as amount, count(*) as cnt"
                    + " from finance_payment_plan where status in ('PLANNED','SCHEDULED')"
                    + currencyClause(currency, "currency")
                    + " group by currency order by currency",
                currency)
            .map(
                r ->
                    new Balance(
                        r.get("currency", String.class),
                        r.get("amount", BigDecimal.class),
                        r.get("cnt", Long.class)));

    // 五路聚合一币种一行。用一个可变的装配器逐路填，而不是每路都重建整行 ——
    // 重建整行那种写法在加第五路时最容易把前面四路的值弄丢。
    var builders = new TreeMap<String, PositionBuilder>();
    for (var row : received) {
      var builder = builders.computeIfAbsent(row.currency(), PositionBuilder::new);
      builder.receivedCash = row.amount();
      builder.unsettledReceived = row.unsettled();
      builder.advance = row.advance();
    }
    for (var row : receivables) {
      var builder = builders.computeIfAbsent(row.currency(), PositionBuilder::new);
      builder.receivableOpen = row.amount();
      builder.receivableCount = row.count();
    }
    for (var row : payables) {
      var builder = builders.computeIfAbsent(row.currency(), PositionBuilder::new);
      builder.payableUnpaid = row.amount();
      builder.payableCount = row.count();
    }
    for (var row : paid) {
      builders.computeIfAbsent(row.currency(), PositionBuilder::new).paidOut = row.amount();
    }
    for (var row : planned) {
      builders.computeIfAbsent(row.currency(), PositionBuilder::new).plannedOutflow = row.amount();
    }

    var positions = new ArrayList<FinanceFundCurrencyPosition>();
    for (var builder : builders.values()) {
      positions.add(
          new FinanceFundCurrencyPosition(
              builder.currency,
              money(builder.receivedCash),
              money(builder.unsettledReceived),
              money(builder.advance),
              money(builder.receivableOpen),
              money(builder.payableUnpaid),
              money(builder.paidOut),
              money(builder.plannedOutflow),
              money(builder.receivableOpen.subtract(builder.payableUnpaid)),
              builder.receivableCount,
              builder.payableCount));
    }

    var notes =
        List.of(
            "累计已到账现金 = finance_receipt 中 RECORDED 收款单的累计金额（历史累计，**不是**在手余额）。",
            "未核销应收 = finance_receivable 的 amount − settled_amount（OPEN / PARTIALLY_SETTLED）。",
            "未付应付 = finance_payable 的 unpaid_amount（OPEN / PARTIALLY_PAID）。",
            "累计已付 = finance_payable.paid_amount 的累计（12.2.2.1 未交付 ⇒ 当前恒为 0，且**没有付款时刻**）。",
            "计划流出 = finance_payment_plan 在册计划的金额（**计划口径**，不是已付）。",
            "计划流出与未付应付**重叠**（计划就是未付应付的排期），两列**不可相加**。",
            "未来结算净额 = 未核销应收 − 未付应付（与汇率敞口的两端同口径）。",
            "账户级余额不可用（12.7.1.1 未交付）：本分布只到币种级。");

    return new FinanceFundCurrencyDistribution(
        today,
        "累计已到账现金是历史累计数，不是在手账户余额 —— 账户余额属 12.7.1.1，当前不存在。",
        false,
        "12.7.1.1",
        List.copyOf(positions),
        notes);
  }

  // ================================================================ 汇率敞口

  /** 汇率敞口测算（实时，不落库）。 */
  public FinanceFundExposureReport exposure(FinanceFundExposureQuery query, String bearer) {
    var asOf = LocalDate.now(ZoneOffset.UTC);
    var base = resolveBaseCurrency(query.baseCurrency());
    var currency = normalizeCurrency(query.currency());

    var receivableOpens =
        fetch(
                "select currency, sum(amount - settled_amount) as open_amount"
                    + " from finance_receivable where status in ('OPEN','PARTIALLY_SETTLED')"
                    + currencyClause(currency, "currency")
                    + " group by currency order by currency",
                currency)
            .map(r -> new Amount(r.get("currency", String.class), r.get("open_amount", BigDecimal.class)));
    var payableOpens =
        fetch(
                "select currency, sum(unpaid_amount) as unpaid"
                    + " from finance_payable where status in ('OPEN','PARTIALLY_PAID')"
                    + currencyClause(currency, "currency")
                    + " group by currency order by currency",
                currency)
            .map(r -> new Amount(r.get("currency", String.class), r.get("unpaid", BigDecimal.class)));

    var receivableMap = new LinkedHashMap<String, BigDecimal>();
    for (var row : receivableOpens) receivableMap.put(row.currency(), row.amount());
    var payableMap = new LinkedHashMap<String, BigDecimal>();
    for (var row : payableOpens) payableMap.put(row.currency(), row.amount());

    var allCurrencies = new TreeSet<String>();
    allCurrencies.addAll(receivableMap.keySet());
    allCurrencies.addAll(payableMap.keySet());

    // 先把本位币口径的净额算出来，再去取汇率 —— 汇率取不到要整体失败。
    var netByCurrency = new LinkedHashMap<String, BigDecimal>();
    for (var ccy : allCurrencies) {
      var receivable = receivableMap.getOrDefault(ccy, BigDecimal.ZERO);
      var payable = payableMap.getOrDefault(ccy, BigDecimal.ZERO);
      netByCurrency.put(ccy, receivable.subtract(payable));
    }

    var rows = new ArrayList<FundAnalysisModels.FinanceFundExposureRow>();
    var baseExposures = new LinkedHashMap<String, BigDecimal>();
    for (var ccy : allCurrencies) {
      var net = netByCurrency.get(ccy);
      BigDecimal rate;
      LocalDate effectiveDate;
      String display;
      if (ccy.equals(base)) {
        // 本位币没有汇率敞口：汇率恒为 1，不去 catalog 取（取不到也不影响）。
        rate = BigDecimal.ONE;
        effectiveDate = asOf;
        display = "1 " + ccy + " = 1 " + base + "（本位币，无汇率敞口）";
      } else {
        var view = rates.latest(ccy, base, asOf, bearer);
        rate = view.rate();
        effectiveDate = view.effectiveDate();
        display = view.display() == null ? "1 " + ccy + " = " + rate.toPlainString() + " " + base : view.display();
      }
      var baseExposure = net.multiply(rate);
      if (!ccy.equals(base)) baseExposures.put(ccy, baseExposure);
      var sensitivity1 = baseExposure.multiply(new BigDecimal("0.01")).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
      var sensitivity5 = baseExposure.multiply(new BigDecimal("0.05")).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
      rows.add(
          new FundAnalysisModels.FinanceFundExposureRow(
              ccy,
              directionOf(net).name(),
              directionOf(net).label(),
              money(receivableMap.getOrDefault(ccy, BigDecimal.ZERO)),
              money(payableMap.getOrDefault(ccy, BigDecimal.ZERO)),
              money(net),
              base,
              rate,
              effectiveDate,
              money(baseExposure),
              sensitivity1,
              sensitivity5,
              null,
              display));
    }

    // 敞口集中度：|本位币敞口| 之和做分母。
    var absoluteTotal = BigDecimal.ZERO;
    for (var value : baseExposures.values()) absoluteTotal = absoluteTotal.add(value.abs());
    var finalRows = new ArrayList<FundAnalysisModels.FinanceFundExposureRow>();
    var totalBase = BigDecimal.ZERO;
    for (var row : rows) {
      BigDecimal share = null;
      if (!row.currency().equals(base) && absoluteTotal.signum() != 0) {
        share =
            row.baseExposureAmount()
                .abs()
                .divide(absoluteTotal, RATIO_SCALE, RoundingMode.HALF_UP);
        totalBase = totalBase.add(row.baseExposureAmount());
      }
      finalRows.add(
          new FundAnalysisModels.FinanceFundExposureRow(
              row.currency(),
              row.direction(),
              row.directionLabel(),
              row.receivableOpenAmount(),
              row.payableOpenAmount(),
              row.netExposureAmount(),
              row.baseCurrency(),
              row.exchangeRate(),
              row.rateEffectiveDate(),
              row.baseExposureAmount(),
              row.sensitivityPer1Pct(),
              row.sensitivityPer5Pct(),
              share,
              row.rateDisplay()));
    }

    return new FinanceFundExposureReport(
        asOf,
        base,
        baseCurrencyBasis(query.baseCurrency()),
        "GET /catalog/exchange-rates/latest（5.2.2.1 汇率查询，跨服务授权 HTTP API）",
        "5.2.2.1",
        "敞口的两端只含应收与应付：存货不在本服务（07 库存核算 / 08 仓储作业 / 13 利润与经营分析），"
            + "蓝图那句「大量外币存货 + 本币负债」里的存货那一半本模块**算不了**，不用 0 冒充。",
        "13.1.1.1",
        money(totalBase),
        List.copyOf(finalRows));
  }

  /**
   * 汇率敞口测算并**落成快照**。
   *
   * <p>快照把「这次测算用了哪天的汇率、汇率是多少、两端金额是多少」一起钉住 ⇒ 事后可复盘。
   * 本位币那一行**不落库**（快照表的 CHECK 钉死 base_currency ≠ currency：
   * 本位币没有汇率敞口，落进去只是噪音）。
   */
  @Transactional
  public FinanceFundExposureMeasureResult measure(
      FinanceFundExposureQuery query, String bearer, AccessService.Access actor) {
    var report = exposure(query, bearer);
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    var created = new ArrayList<FundAnalysisModels.FinanceFundExposureSnapshotRow>();
    for (var row : report.rows()) {
      if (row.currency().equals(report.baseCurrency())) continue;
      var id = UUID.randomUUID();
      var no = nextReference("EXPO");
      sql.execute(
          "insert into finance_fund_exposure_snapshot(id,tenant_id,snapshot_no,as_of_date,"
              + " base_currency,currency,direction,receivable_open_amount,payable_open_amount,"
              + " net_exposure_amount,exchange_rate,rate_effective_date,base_exposure_amount,"
              + " sensitivity_per_1pct,sensitivity_per_5pct,fund_account_ref,"
              + " fund_account_owner_module,bank_statement_ref,bank_statement_owner_module,"
              + " account_level_available,note,created_at)"
              + " values(?,?,?,?::date,?,?,?,?,?,?,?,?::date,?,?,?,null,'12.7.1.1',null,'12.7.1.2',"
              + " false,'',?::timestamptz)",
          id,
          actor.tenantId(),
          no,
          report.asOfDate(),
          report.baseCurrency(),
          row.currency(),
          row.direction(),
          row.receivableOpenAmount(),
          row.payableOpenAmount(),
          row.netExposureAmount(),
          row.exchangeRate(),
          row.rateEffectiveDate(),
          row.baseExposureAmount(),
          row.sensitivityPer1Pct(),
          row.sensitivityPer5Pct(),
          now);
      created.add(
          new FundAnalysisModels.FinanceFundExposureSnapshotRow(
              id,
              no,
              report.asOfDate(),
              report.baseCurrency(),
              row.currency(),
              row.direction(),
              row.receivableOpenAmount(),
              row.payableOpenAmount(),
              row.netExposureAmount(),
              row.exchangeRate(),
              row.rateEffectiveDate(),
              row.baseExposureAmount(),
              row.sensitivityPer1Pct(),
              row.sensitivityPer5Pct(),
              now));
    }
    return new FinanceFundExposureMeasureResult(report, List.copyOf(created));
  }

  /** 敞口快照列表（真分页 + 真排序）。 */
  public FinanceFundExposureSnapshotPage exposureSnapshots(int page, int size) {
    var safePage = Math.max(page, 0);
    var safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    var total =
        sql.fetchOne("select count(*) from finance_fund_exposure_snapshot").get(0, Long.class);
    var rows =
        sql.fetch(
                "select id,snapshot_no,as_of_date,base_currency,currency,direction,"
                    + " receivable_open_amount,payable_open_amount,net_exposure_amount,"
                    + " exchange_rate,rate_effective_date,base_exposure_amount,"
                    + " sensitivity_per_1pct,sensitivity_per_5pct,created_at"
                    + " from finance_fund_exposure_snapshot"
                    + " order by as_of_date desc, created_at desc, id"
                    + " limit ? offset ?",
                safeSize,
                safePage * safeSize)
            .map(
                r ->
                    new FundAnalysisModels.FinanceFundExposureSnapshotRow(
                        r.get("id", UUID.class),
                        r.get("snapshot_no", String.class),
                        r.get("as_of_date", LocalDate.class),
                        r.get("base_currency", String.class),
                        r.get("currency", String.class),
                        r.get("direction", String.class),
                        r.get("receivable_open_amount", BigDecimal.class),
                        r.get("payable_open_amount", BigDecimal.class),
                        r.get("net_exposure_amount", BigDecimal.class),
                        r.get("exchange_rate", BigDecimal.class),
                        r.get("rate_effective_date", LocalDate.class),
                        r.get("base_exposure_amount", BigDecimal.class),
                        r.get("sensitivity_per_1pct", BigDecimal.class),
                        r.get("sensitivity_per_5pct", BigDecimal.class),
                        r.get("created_at", OffsetDateTime.class)));
    return new FundAnalysisModels.FinanceFundExposureSnapshotPage(
        List.copyOf(rows), safePage, safeSize, total);
  }

  // ================================================================ 资金周转天数

  /**
   * 资金周转天数：按币种分开。
   *
   * <p>⚠️ 分子分母必须**同币种、同日历日口径**：
   * DSO 的分母是**期间实际现金流入**（已到账收款），DPO 的分母是**期间应付发生额**
   * （确认日落在期间内的应付单金额）—— 不用「实际付款」当分母，因为实际付款当前没有日期维度。
   */
  public FinanceFundTurnoverReport turnover(FinanceFundTurnoverQuery query) {
    var today = LocalDate.now(ZoneOffset.UTC);
    var from = query.fromDate() != null ? query.fromDate() : today.minusDays(DEFAULT_RANGE_DAYS - 1L);
    var to = query.toDate() != null ? query.toDate() : today;
    validateRange(from, to);
    var currency = normalizeCurrency(query.currency());
    var periodDays = (int) (ChronoUnit.DAYS.between(from, to) + 1);

    var where = currencyClause(currency, "u.currency");
    var binds = new ArrayList<Object>();
    binds.add(from);
    binds.add(to);
    binds.add(from);
    binds.add(to);
    binds.add(periodDays);
    binds.add(periodDays);
    if (currency != null) binds.add(currency);

    var sqlText =
        "with uni as ("
            + " select currency from finance_receivable where status in ('OPEN','PARTIALLY_SETTLED')"
            + " union select currency from finance_receipt where status = 'RECORDED'"
            + " union select currency from finance_payable where status in ('OPEN','PARTIALLY_PAID','PAID')"
            + "), recv as ("
            + " select currency, sum(amount - settled_amount) as receivable_open"
            + " from finance_receivable where status in ('OPEN','PARTIALLY_SETTLED') group by currency"
            + "), inflow as ("
            + " select currency, sum(amount) as inflow_amount from finance_receipt"
            + " where status = 'RECORDED'"
            + " and (received_at at time zone 'UTC')::date >= ?::date"
            + " and (received_at at time zone 'UTC')::date <= ?::date group by currency"
            + "), pay as ("
            + " select currency, sum(unpaid_amount) as payable_unpaid from finance_payable"
            + " where status in ('OPEN','PARTIALLY_PAID') group by currency"
            + "), occ as ("
            + " select currency, sum(amount) as payable_occurrence from finance_payable"
            + " where status in ('OPEN','PARTIALLY_PAID','PAID')"
            + " and (confirmed_at at time zone 'UTC')::date >= ?::date"
            + " and (confirmed_at at time zone 'UTC')::date <= ?::date group by currency"
            + ") select u.currency as currency,"
            + " coalesce(recv.receivable_open, 0) as receivable_open,"
            + " coalesce(inflow.inflow_amount, 0) as inflow_amount,"
            + " coalesce(pay.payable_unpaid, 0) as payable_unpaid,"
            + " coalesce(occ.payable_occurrence, 0) as payable_occurrence,"
            + " round(coalesce(recv.receivable_open, 0) / nullif(coalesce(inflow.inflow_amount, 0), 0) * ?::int, 4)"
            + "   as dso_days,"
            + " round(coalesce(pay.payable_unpaid, 0) / nullif(coalesce(occ.payable_occurrence, 0), 0) * ?::int, 4)"
            + "   as dpo_days"
            + " from uni u"
            + " left join recv on recv.currency = u.currency"
            + " left join inflow on inflow.currency = u.currency"
            + " left join pay on pay.currency = u.currency"
            + " left join occ on occ.currency = u.currency"
            + where
            + " order by u.currency";

    var rows = new ArrayList<FundAnalysisModels.FinanceFundTurnoverRow>();
    for (var r : sql.fetch(sqlText, binds.toArray())) {
      var dso = r.get("dso_days", BigDecimal.class);
      var dpo = r.get("dpo_days", BigDecimal.class);
      BigDecimal turnoverDays = null;
      if (dso != null && dpo != null) {
        turnoverDays = dso.subtract(dpo).setScale(DAYS_SCALE, RoundingMode.HALF_UP);
      }
      rows.add(
          new FundAnalysisModels.FinanceFundTurnoverRow(
              r.get("currency", String.class),
              periodDays,
              money(r.get("receivable_open", BigDecimal.class)),
              money(r.get("inflow_amount", BigDecimal.class)),
              money(r.get("payable_unpaid", BigDecimal.class)),
              money(r.get("payable_occurrence", BigDecimal.class)),
              dso == null ? null : dso.setScale(DAYS_SCALE, RoundingMode.HALF_UP),
              dpo == null ? null : dpo.setScale(DAYS_SCALE, RoundingMode.HALF_UP),
              turnoverDays,
              null,
              false,
              "13.1.1.1",
              "DPO 的分母是**期间应付发生额**（确认日落在期间内的应付金额），"
                  + "不是期间付款额 —— 实际付款当前没有日期维度（12.2.2.1 未交付）。"));
    }

    return new FinanceFundTurnoverReport(
        from,
        to,
        periodDays,
        ACTUAL_OUTFLOW_BASIS,
        ACTUAL_OUTFLOW_OWNER,
        List.copyOf(rows));
  }

  // ================================================================ 付款集中度

  /** 付款集中度：时间维度 + 付款对象维度（口径 = **计划付款**）。 */
  public FinanceFundPaymentConcentrationReport concentration(FinanceFundConcentrationQuery query) {
    var today = LocalDate.now(ZoneOffset.UTC);
    var from = query.fromDate() != null ? query.fromDate() : today;
    var to = query.toDate() != null ? query.toDate() : today.plusDays(DEFAULT_RANGE_DAYS - 1L);
    validateRange(from, to);
    var granularity = query.granularity() != null ? query.granularity() : FinanceFundTrendGranularity.WEEK;
    var currency = normalizeCurrency(query.currency());

    var bucketsByCurrency = paymentBuckets(from, to, granularity, currency);
    var counterparties = paymentCounterparties(from, to, currency);

    var rows = new ArrayList<FundAnalysisModels.FinanceFundPaymentConcentration>();
    for (var entry : bucketsByCurrency.entrySet()) {
      var ccy = entry.getKey();
      var buckets = entry.getValue();
      var total = BigDecimal.ZERO;
      for (var bucket : buckets) total = total.add(bucket.amount());
      var planCount =
          sql.fetchOne(
                  "select count(*) from finance_payment_plan"
                      + " where status in ('PLANNED','SCHEDULED') and currency = ?"
                      + " and planned_paid_date >= ?::date and planned_paid_date <= ?::date",
                  ccy,
                  from,
                  to)
              .get(0, Long.class);

      var bucketViews = new ArrayList<FundAnalysisModels.FinanceFundConcentrationBucket>();
      BigDecimal topShare = null;
      LocalDate topPeriod = null;
      for (var bucket : buckets) {
        var share =
            total.signum() == 0
                ? null
                : bucket.amount().divide(total, RATIO_SCALE, RoundingMode.HALF_UP);
        bucketViews.add(
            new FundAnalysisModels.FinanceFundConcentrationBucket(bucket.period(), money(bucket.amount()), share));
        if (share != null && (topShare == null || share.compareTo(topShare) > 0)) {
          topShare = share;
          topPeriod = bucket.period();
        }
      }

      var counterpartyViews = new ArrayList<FundAnalysisModels.FinanceFundCounterpartyShare>();
      BigDecimal topCounterpartyShare = null;
      for (var cp : counterparties.getOrDefault(ccy, List.of())) {
        var share =
            total.signum() == 0
                ? null
                : cp.amount().divide(total, RATIO_SCALE, RoundingMode.HALF_UP);
        counterpartyViews.add(
            new FundAnalysisModels.FinanceFundCounterpartyShare(cp.name(), money(cp.amount()), share));
        if (share != null
            && (topCounterpartyShare == null || share.compareTo(topCounterpartyShare) > 0)) {
          topCounterpartyShare = share;
        }
      }

      rows.add(
          new FundAnalysisModels.FinanceFundPaymentConcentration(
              ccy,
              money(total),
              planCount,
              List.copyOf(bucketViews),
              topShare,
              topPeriod,
              List.copyOf(counterpartyViews),
              topCounterpartyShare));
    }

    return new FundAnalysisModels.FinanceFundPaymentConcentrationReport(
        from,
        to,
        granularity.name(),
        "口径 = **计划付款**（finance_payment_plan 在册计划，按 planned_paid_date 分桶）。"
            + "12.1.2.2 的计划表写不出「已付款」，所以这不是「已付集中度」。",
        List.copyOf(rows));
  }

  // ================================================================ 流动性预警

  /** 流动性预警：四条规则跑在真实聚合上。 */
  public FinanceFundLiquidityAlertReport alerts(FinanceFundLiquidityAlertQuery query) {
    var today = LocalDate.now(ZoneOffset.UTC);
    var from = query.fromDate() != null ? query.fromDate() : today;
    var to = query.toDate() != null ? query.toDate() : today.plusDays(DEFAULT_RANGE_DAYS - 1L);
    validateRange(from, to);
    var periodDays = (int) (ChronoUnit.DAYS.between(from, to) + 1);

    var config = thresholdConfig();
    var thresholds = config.thresholds();
    var flows = windowFlows(from, to, null);
    var concentration = concentration(new FinanceFundConcentrationQuery(null, from, to, FinanceFundTrendGranularity.WEEK));
    var concentrationByCurrency = new HashMap<String, FundAnalysisModels.FinanceFundPaymentConcentration>();
    for (var row : concentration.rows()) concentrationByCurrency.put(row.currency(), row);

    var overdueByCurrency = overdueRatios(today);
    var alerts = new ArrayList<FundAnalysisModels.FinanceFundLiquidityAlert>();

    for (var flow : flows.values()) {
      var ccy = flow.currency();

      // ① 付款集中度：单期计划付款额占窗口总额的比例上限。
      var concentrationRow = concentrationByCurrency.get(ccy);
      var concentrationThreshold = thresholdOf(thresholds, FinanceFundLiquidityRule.PAYMENT_CONCENTRATION);
      if (concentrationRow != null
          && concentrationRow.topBucketShare() != null
          && concentrationThreshold != null
          && concentrationThreshold.enabled()) {
        var observed = concentrationRow.topBucketShare();
        if (observed.compareTo(concentrationThreshold.thresholdValue()) >= 0) {
          alerts.add(
              alert(
                  FinanceFundLiquidityRule.PAYMENT_CONCENTRATION,
                  severityAbove(observed, concentrationThreshold.thresholdValue()),
                  ccy,
                  "付款集中度过高",
                  "最集中的一个付款窗口占窗口内计划付款额的 "
                      + percent(observed)
                      + "（阈值 "
                      + percent(concentrationThreshold.thresholdValue())
                      + "）。",
                  "窗口内计划付款额按 UTC 日历周分桶",
                  observed,
                  concentrationThreshold.thresholdValue(),
                  from,
                  to));
        }
      }

      // ② 净流出缺口：计划流出 ÷（实际流入 + 预期流入）。
      var gapThreshold = thresholdOf(thresholds, FinanceFundLiquidityRule.NET_OUTFLOW_GAP);
      if (gapThreshold != null && gapThreshold.enabled()) {
        var inflow = flow.actualInflow().add(flow.expectedInflow());
        if (inflow.signum() > 0) {
          var observed =
              flow.plannedOutflow()
                  .divide(inflow, RATIO_SCALE, RoundingMode.HALF_UP);
          if (observed.compareTo(gapThreshold.thresholdValue()) >= 0) {
            alerts.add(
                alert(
                    FinanceFundLiquidityRule.NET_OUTFLOW_GAP,
                    severityAbove(observed, gapThreshold.thresholdValue()),
                    ccy,
                    "窗口内净流出缺口",
                    "窗口内计划流出是流入的 "
                        + percent(observed)
                        + "（阈值 "
                        + percent(gapThreshold.thresholdValue())
                        + "）。计划流出超过流入 ⇒ 窗口内要自己垫钱。",
                    "计划流出（付款计划）÷（实际到账流入 + 预期流入）",
                    observed,
                    gapThreshold.thresholdValue(),
                    from,
                    to));
          }
        }
      }

      // ③ 应收逾期：逾期未核销应收 ÷ 未核销应收。
      var overdueThreshold = thresholdOf(thresholds, FinanceFundLiquidityRule.RECEIVABLE_OVERDUE);
      var overdue = overdueByCurrency.get(ccy);
      if (overdueThreshold != null
          && overdueThreshold.enabled()
          && overdue != null
          && overdue.total().signum() > 0) {
        var observed =
            overdue.overdue().divide(overdue.total(), RATIO_SCALE, RoundingMode.HALF_UP);
        if (observed.compareTo(overdueThreshold.thresholdValue()) >= 0) {
          alerts.add(
              alert(
                  FinanceFundLiquidityRule.RECEIVABLE_OVERDUE,
                  severityAbove(observed, overdueThreshold.thresholdValue()),
                  ccy,
                  "未核销应收逾期比例偏高",
                  "逾期未核销应收占该币种未核销应收的 "
                      + percent(observed)
                      + "（阈值 "
                      + percent(overdueThreshold.thresholdValue())
                      + "）。",
                  "（未核销应收中已过到期日的部分）÷（未核销应收）",
                  observed,
                  overdueThreshold.thresholdValue(),
                  from,
                  to));
        }
      }

      // ④ 现金覆盖天数：窗口内实际流入按日均计划流出能支撑多少天（**下限**规则）。
      var bufferThreshold = thresholdOf(thresholds, FinanceFundLiquidityRule.CASH_BUFFER_DAYS);
      if (bufferThreshold != null
          && bufferThreshold.enabled()
          && flow.plannedOutflow().signum() > 0) {
        var dailyOutflow = flow.plannedOutflow().divide(BigDecimal.valueOf(periodDays), 8, RoundingMode.HALF_UP);
        var observed =
            flow.actualInflow().divide(dailyOutflow, DAYS_SCALE, RoundingMode.HALF_UP);
        if (observed.compareTo(bufferThreshold.thresholdValue()) <= 0) {
          alerts.add(
              alert(
                  FinanceFundLiquidityRule.CASH_BUFFER_DAYS,
                  severityBelow(observed, bufferThreshold.thresholdValue()),
                  ccy,
                  "现金覆盖天数不足",
                  "窗口内实际到账流入按日均计划流出只能支撑 "
                      + observed.toPlainString()
                      + " 天（阈值 "
                      + bufferThreshold.thresholdValue().toPlainString()
                      + " 天）。",
                  "窗口内实际流入 ÷（窗口内计划流出 ÷ 窗口天数）",
                  observed,
                  bufferThreshold.thresholdValue(),
                  from,
                  to));
        }
      }
    }

    alerts.sort(
        Comparator.comparing((FundAnalysisModels.FinanceFundLiquidityAlert a) -> a.currency())
            .thenComparing(a -> a.rule()));

    return new FundAnalysisModels.FinanceFundLiquidityAlertReport(
        from,
        to,
        config.source(),
        alerts.size(),
        List.copyOf(alerts),
        thresholds,
        ACTUAL_OUTFLOW_BASIS,
        ACTUAL_OUTFLOW_OWNER);
  }

  // ================================================================ 阈值配置

  /** 生效中的阈值配置：租户配过的用租户的，没配过用蓝图默认档。 */
  public FinanceFundLiquidityThresholdConfig thresholdConfig() {
    var rows =
        sql.fetch(
                "select code,label,threshold_value,enabled,note from finance_liquidity_threshold"
                    + " order by code")
            .map(
                r ->
                    new FundAnalysisModels.FinanceFundLiquidityThreshold(
                        r.get("code", String.class),
                        r.get("label", String.class),
                        r.get("threshold_value", BigDecimal.class),
                        r.get("enabled", Boolean.class),
                        r.get("note", String.class)));
    if (rows.isEmpty()) {
      return new FinanceFundLiquidityThresholdConfig(
          "BLUEPRINT_DEFAULT",
          false,
          BLUEPRINT_THRESHOLDS.stream()
              .map(
                  t ->
                      new FundAnalysisModels.FinanceFundLiquidityThreshold(
                          t.rule().name(), t.label(), t.value(), true, t.note()))
              .toList(),
          "当前生效的是**蓝图默认档**（租户没配过）。默认档不是「必须配的」——"
              + "配了就用租户的值，不配就用默认的，响应里会标出 source。");
    }
    // 租户配了一部分 ⇒ 其余的仍用默认档补齐（configured=true 表明「其中有租户自己的值」）。
    var byCode = new HashMap<String, FundAnalysisModels.FinanceFundLiquidityThreshold>();
    for (var row : rows) byCode.put(row.code(), row);
    var merged =
        BLUEPRINT_THRESHOLDS.stream()
            .map(
                t ->
                    byCode.getOrDefault(
                        t.rule().name(),
                        new FundAnalysisModels.FinanceFundLiquidityThreshold(
                            t.rule().name(), t.label(), t.value(), true, t.note())))
            .toList();
    return new FinanceFundLiquidityThresholdConfig(
        "TENANT_CONFIG",
        true,
        merged,
        "租户自己配的阈值（未配的规则沿用蓝图默认档）。");
  }

  /**
   * 配置流动性预警阈值（**整体替换**）。
   *
   * <p>⚠️ 整体替换而不是逐条增删改：规则的**组合**才是口径（哪条开着、阈值多少），
   * 逐条写会出现「只配了一半」的中间态，那种状态下预警集合没有确定含义。
   * 传空列表 = 回到**蓝图默认档**。
   */
  @Transactional
  public FinanceFundLiquidityThresholdConfig updateThresholds(
      FundAnalysisModels.FinanceFundLiquidityThresholdConfigInput input,
      AccessService.Access actor) {
    var thresholds = input.thresholds() == null ? List.<FundAnalysisModels.FinanceFundLiquidityThresholdInput>of() : input.thresholds();
    if (thresholds.isEmpty()) {
      sql.execute("delete from finance_liquidity_threshold");
      return thresholdConfig();
    }

    var known = new HashMap<String, FinanceFundLiquidityRule>();
    for (var rule : FinanceFundLiquidityRule.values()) known.put(rule.name(), rule);
    var seen = new HashSet<String>();
    for (var item : thresholds) {
      if (item.code() == null || !known.containsKey(item.code())) {
        throw badRequest(
            "未知的规则码「"
                + item.code()
                + "」：可用的规则是 "
                + String.join("、", known.keySet()));
      }
      if (!seen.add(item.code())) {
        throw badRequest("规则「" + item.code() + "」重复了：同一规则只能有一条阈值");
      }
      if (item.thresholdValue() == null || item.thresholdValue().signum() < 0) {
        throw badRequest("规则「" + item.code() + "」的阈值必须是不小于 0 的数字");
      }
      if (item.label() == null || item.label().isBlank()) {
        throw badRequest("规则「" + item.code() + "」的名称不能为空");
      }
    }
    for (var rule : FinanceFundLiquidityRule.values()) {
      if (!seen.contains(rule.name())) {
        throw badRequest(
            "规则「"
                + rule.name()
                + "」缺失：阈值配置是**整体替换**，四条规则必须**全部**给出"
                + "（不想启用某条就把 enabled 置为 false）。");
      }
    }

    var now = OffsetDateTime.now(ZoneOffset.UTC);
    sql.execute("delete from finance_liquidity_threshold");
    for (var item : thresholds) {
      sql.execute(
          "insert into finance_liquidity_threshold(id,tenant_id,code,label,threshold_value,"
              + " enabled,note,version,created_at,updated_at)"
              + " values(?,?,?,?,?,?,?,0,?::timestamptz,?::timestamptz)",
          UUID.randomUUID(),
          actor.tenantId(),
          item.code(),
          item.label().trim(),
          item.thresholdValue(),
          item.enabled(),
          item.note() == null ? "" : item.note(),
          now,
          now);
    }
    return thresholdConfig();
  }

  // ================================================================ 内部：窗口聚合

  /** 窗口内每个币种的三路资金（真实聚合）。 */
  Map<String, WindowFlow> windowFlows(LocalDate from, LocalDate to, String currency) {
    var actual =
        fetch(
                "select currency, sum(amount) as amount from finance_receipt"
                    + " where status = 'RECORDED'"
                    + " and (received_at at time zone 'UTC')::date >= ?::date"
                    + " and (received_at at time zone 'UTC')::date <= ?::date"
                    + currencyClause(currency, "currency")
                    + " group by currency",
                List.of(from, to),
                currency)
            .map(r -> new Amount(r.get("currency", String.class), r.get("amount", BigDecimal.class)));
    var expected =
        fetch(
                "select currency, sum(amount - settled_amount) as amount from finance_receivable"
                    + " where status in ('OPEN','PARTIALLY_SETTLED')"
                    + " and due_date >= ?::date and due_date <= ?::date"
                    + currencyClause(currency, "currency")
                    + " group by currency",
                List.of(from, to),
                currency)
            .map(r -> new Amount(r.get("currency", String.class), r.get("amount", BigDecimal.class)));
    var planned =
        fetch(
                "select currency, sum(planned_amount) as amount from finance_payment_plan"
                    + " where status in ('PLANNED','SCHEDULED')"
                    + " and planned_paid_date >= ?::date and planned_paid_date <= ?::date"
                    + currencyClause(currency, "currency")
                    + " group by currency",
                List.of(from, to),
                currency)
            .map(r -> new Amount(r.get("currency", String.class), r.get("amount", BigDecimal.class)));

    var currencies = new TreeSet<String>();
    actual.forEach(a -> currencies.add(a.currency()));
    expected.forEach(a -> currencies.add(a.currency()));
    planned.forEach(a -> currencies.add(a.currency()));
    var out = new LinkedHashMap<String, WindowFlow>();
    for (var ccy : currencies) {
      out.put(
          ccy,
          new WindowFlow(
              ccy,
              amountOf(actual, ccy),
              amountOf(expected, ccy),
              amountOf(planned, ccy)));
    }
    return out;
  }

  /** 每个币种的「逾期未核销应收 / 未核销应收」（金额，不是比例）。 */
  Map<String, OverdueAmounts> overdueRatios(LocalDate asOf) {
    var rows =
        sql.fetch(
                "select currency,"
                    + " sum(amount - settled_amount) as total,"
                    + " sum(case when due_date < ?::date then amount - settled_amount else 0 end) as overdue"
                    + " from finance_receivable where status in ('OPEN','PARTIALLY_SETTLED')"
                    + " group by currency",
                asOf)
            .map(
                r ->
                    new OverdueAmounts(
                        r.get("currency", String.class),
                        r.get("total", BigDecimal.class),
                        r.get("overdue", BigDecimal.class)));
    var out = new LinkedHashMap<String, OverdueAmounts>();
    for (var row : rows) out.put(row.currency(), row);
    return out;
  }

  /** 按计划付款日分桶的计划付款额（每个币种一组）。 */
  Map<String, List<BucketAmount>> paymentBuckets(
      LocalDate from, LocalDate to, FinanceFundTrendGranularity granularity, String currency) {
    var rows =
        fetch(
                "select "
                    + periodExpr(granularity, "p.planned_paid_date", false)
                    + " as period_start, p.currency, sum(p.planned_amount) as amount"
                    + " from finance_payment_plan p"
                    + " where p.status in ('PLANNED','SCHEDULED')"
                    + " and p.planned_paid_date >= ?::date and p.planned_paid_date <= ?::date"
                    + currencyClause(currency, "p.currency")
                    + " group by 1, 2 order by 1, 2",
                List.of(from, to),
                currency);
    var out = new LinkedHashMap<String, List<BucketAmount>>();
    for (var r : rows) {
      var ccy = r.get("currency", String.class);
      out.computeIfAbsent(ccy, k -> new ArrayList<>())
          .add(
              new BucketAmount(
                  r.get("period_start", LocalDate.class), r.get("amount", BigDecimal.class)));
    }
    // 桶之间**不留缝**：把窗口内该粒度的完整桶序列补齐，空桶也要在（集中度才不会被
    // 「少一个桶」稀释）。
    var byCurrency = new LinkedHashMap<String, List<BucketAmount>>();
    for (var entry : out.entrySet()) {
      var buckets = entry.getValue();
      if (buckets.isEmpty()) {
        byCurrency.put(entry.getKey(), buckets);
        continue;
      }
      var min = buckets.get(0).period();
      var max = buckets.get(buckets.size() - 1).period();
      var amounts = new HashMap<LocalDate, BigDecimal>();
      for (var bucket : buckets) amounts.put(bucket.period(), bucket.amount());
      var filled = new ArrayList<BucketAmount>();
      for (var period : periodSequence(min, max, granularity)) {
        filled.add(new BucketAmount(period, amounts.getOrDefault(period, BigDecimal.ZERO)));
      }
      byCurrency.put(entry.getKey(), filled);
    }
    return byCurrency;
  }

  /** 按付款对象分组的计划付款额（每个币种一组）。 */
  Map<String, List<CounterpartyAmount>> paymentCounterparties(
      LocalDate from, LocalDate to, String currency) {
    var rows =
        fetch(
                "select p.currency, pay.counterparty_name, sum(p.planned_amount) as amount"
                    + " from finance_payment_plan p"
                    + " join finance_payable pay"
                    + "   on pay.tenant_id = p.tenant_id and pay.id = p.payable_id"
                    + " where p.status in ('PLANNED','SCHEDULED')"
                    + " and p.planned_paid_date >= ?::date and p.planned_paid_date <= ?::date"
                    + currencyClause(currency, "p.currency")
                    + " group by 1, 2 order by 3 desc",
                List.of(from, to),
                currency);
    var out = new LinkedHashMap<String, List<CounterpartyAmount>>();
    for (var r : rows) {
      var ccy = r.get("currency", String.class);
      out.computeIfAbsent(ccy, k -> new ArrayList<>())
          .add(
              new CounterpartyAmount(
                  r.get("counterparty_name", String.class), r.get("amount", BigDecimal.class)));
    }
    return out;
  }

  // ================================================================ 内部：工具

  /** 趋势单元的键。 */
  record CellKey(LocalDate period, String currency) {}

  /** 内部形态：币种 + 一个金额。 */
  record Amount(String currency, BigDecimal amount) {}

  /** 内部形态：币种 + 余额 + 笔数。 */
  record Balance(String currency, BigDecimal amount, long count) {}

  /** 内部形态：一个币种的未核销应收总额与其中的逾期部分。 */
  record OverdueAmounts(String currency, BigDecimal total, BigDecimal overdue) {}

  /** 内部形态：收款聚合。 */
  record Flow(
      String currency,
      BigDecimal amount,
      BigDecimal unsettled,
      BigDecimal advance,
      long count) {}

  /** 内部形态：币种资金分布的装配器（五路聚合逐路填进来，最后一次成型）。 */
  static final class PositionBuilder {
    final String currency;
    BigDecimal receivedCash = BigDecimal.ZERO;
    BigDecimal unsettledReceived = BigDecimal.ZERO;
    BigDecimal advance = BigDecimal.ZERO;
    BigDecimal receivableOpen = BigDecimal.ZERO;
    BigDecimal payableUnpaid = BigDecimal.ZERO;
    BigDecimal paidOut = BigDecimal.ZERO;
    BigDecimal plannedOutflow = BigDecimal.ZERO;
    long receivableCount;
    long payableCount;

    PositionBuilder(String currency) {
      this.currency = currency;
    }
  }

  /** 内部形态：付款对象聚合。 */
  record CounterpartyAmount(String name, BigDecimal amount) {}

  private Map<CellKey, Cell> flowCells(
      String sqlText, List<Object> binds, String currency) {
    var all = new ArrayList<Object>(binds);
    if (currency != null) all.add(currency);
    var out = new HashMap<CellKey, Cell>();
    for (var r : sql.fetch(sqlText, all.toArray())) {
      out.put(
          new CellKey(r.get("period_start", LocalDate.class), r.get("currency", String.class)),
          new Cell(r.get("amount", BigDecimal.class), r.get("cnt", Long.class)));
    }
    return out;
  }

  private static BigDecimal sumOf(Map<CellKey, Cell> map, String currency) {
    var total = BigDecimal.ZERO;
    for (var entry : map.entrySet()) {
      if (entry.getKey().currency().equals(currency)) total = total.add(entry.getValue().amount());
    }
    return total;
  }

  private static BigDecimal amountOf(List<Amount> rows, String currency) {
    for (var row : rows) {
      if (row.currency().equals(currency)) return row.amount();
    }
    return BigDecimal.ZERO;
  }

  /**
   * 分桶表达式：一律按 **UTC 日历日**切。
   *
   * <p>⚠️ timestamptz 列必须先 {@code at time zone 'UTC'} 再取日期 ——
   * 直接 {@code col::date} 用的是**会话时区**，换一条连接就换一套分桶，分布会凭空多出空桶。
   */
  static String periodExpr(FinanceFundTrendGranularity granularity, String column, boolean timestamptz) {
    var base = timestamptz ? "(" + column + " at time zone 'UTC')" : column + "::timestamp";
    return switch (granularity) {
      case DAY -> base + "::date";
      case WEEK -> "date_trunc('week'," + base + ")::date";
      case MONTH -> "date_trunc('month'," + base + ")::date";
    };
  }

  /** 桶序列：从 min 到 max，**不留缝**（缺桶 = 分布凭空少一格）。 */
  static List<LocalDate> periodSequence(LocalDate min, LocalDate max, FinanceFundTrendGranularity granularity) {
    var out = new ArrayList<LocalDate>();
    var cursor =
        switch (granularity) {
          case DAY -> min;
          case WEEK -> min.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
          case MONTH -> min.withDayOfMonth(1);
        };
    while (!cursor.isAfter(max)) {
      out.add(cursor);
      cursor =
          switch (granularity) {
            case DAY -> cursor.plusDays(1);
            case WEEK -> cursor.plusWeeks(1);
            case MONTH -> cursor.plusMonths(1);
          };
    }
    return out;
  }

  private static FinanceFundTrendGranularity defaultGranularity(LocalDate from, LocalDate to) {
    var days = ChronoUnit.DAYS.between(from, to) + 1;
    return days <= 62 ? FinanceFundTrendGranularity.DAY : FinanceFundTrendGranularity.WEEK;
  }

  private static String currencyClause(String currency, String column) {
    return currency == null ? "" : " and " + column + " = ?";
  }

  /**
   * 查一张表：只有币种筛选这一个可选绑定。
   *
   * <p>⚠️ 币种筛选是**可选**的：拼进 SQL 的 {@code ?} 就必须**真的有绑定**，
   * 否则 jOOQ 会在执行期报「缺绑定」—— 那种错看起来像 SQL 写错了，其实是绑定漏了。
   * 所以这里把「有没有币种」收敛到一处，不让每个查询各写一遍 if。
   */
  private org.jooq.Result<org.jooq.Record> fetch(String sqlText, String currency) {
    return currency == null ? sql.fetch(sqlText) : sql.fetch(sqlText, currency);
  }

  /** 查一张表：固定绑定 + 可选币种绑定（可选绑定排在最后）。 */
  private org.jooq.Result<org.jooq.Record> fetch(
      String sqlText, List<Object> binds, String currency) {
    var all = new ArrayList<Object>(binds);
    if (currency != null) all.add(currency);
    return sql.fetch(sqlText, all.toArray());
  }

  private static String normalizeCurrency(String currency) {
    if (currency == null || currency.isBlank()) return null;
    var normalized = currency.trim().toUpperCase(Locale.ROOT);
    if (!normalized.matches("[A-Z]{3}")) {
      throw badRequest("币种请用三位大写币种代码（如 USD）");
    }
    return normalized;
  }

  private void validateRange(LocalDate from, LocalDate to) {
    if (from.isAfter(to)) {
      throw badRequest("起始日期不能晚于结束日期");
    }
    if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_RANGE_DAYS) {
      throw badRequest("区间最长 " + MAX_RANGE_DAYS + " 天，请缩小范围");
    }
  }

  /**
   * 解析本位币。
   *
   * <p>取不到就**显式失败**：本位币是敞口折算的目标币种，缺了它整个敞口算不出来。
   * 静默挑一个（哪怕是「第一个」）会产出**看着像真的**数字 —— 那比报错更糟。
   */
  private String resolveBaseCurrency(String requested) {
    if (requested != null && !requested.isBlank()) {
      var normalized = requested.trim().toUpperCase(Locale.ROOT);
      if (!normalized.matches("[A-Z]{3}")) {
        throw badRequest("本位币请用三位大写币种代码（如 USD）");
      }
      return normalized;
    }
    var candidates =
        sql.fetch(
                "select base_currency from finance_receivable where base_currency is not null"
                    + " union select base_currency from finance_payable where base_currency is not null"
                    + " order by base_currency")
            .map(r -> r.get(0, String.class));
    if (candidates.isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "读不到本位币，汇率敞口无法测算："
              + "请显式传入 baseCurrency，或先在应收 / 应付单上登记汇率快照"
              + "（本位币取自单据上的 base_currency）");
    }
    if (candidates.size() > 1) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "本位币不唯一（本租户单据上记了 "
              + String.join("、", candidates)
              + "）：请显式传入 baseCurrency");
    }
    return candidates.get(0);
  }

  private String baseCurrencyBasis(String requested) {
    return requested == null || requested.isBlank()
        ? "取自本租户单据上已记的汇率快照本位币（base_currency）"
        : "调用方显式指定";
  }

  private static FinanceFundExposureDirection directionOf(BigDecimal net) {
    var sign = net.signum();
    if (sign > 0) return FinanceFundExposureDirection.LONG_ASSET;
    if (sign < 0) return FinanceFundExposureDirection.SHORT_ASSET;
    return FinanceFundExposureDirection.FLAT;
  }

  private static FundAnalysisModels.FinanceFundLiquidityThreshold thresholdOf(
      List<FundAnalysisModels.FinanceFundLiquidityThreshold> thresholds, FinanceFundLiquidityRule rule) {
    for (var threshold : thresholds) {
      if (threshold.code().equals(rule.name())) return threshold;
    }
    return null;
  }

  /** 「越大越糟」型规则：达到阈值即预警，达到两倍即严重。 */
  private static String severityAbove(BigDecimal observed, BigDecimal threshold) {
    if (threshold.signum() > 0 && observed.compareTo(threshold.multiply(new BigDecimal("2"))) >= 0) {
      return FinanceFundAlertSeverity.CRITICAL.name();
    }
    return FinanceFundAlertSeverity.WARN.name();
  }

  /** 「越小越糟」型规则：低于阈值即预警，低于一半即严重。 */
  private static String severityBelow(BigDecimal observed, BigDecimal threshold) {
    if (threshold.signum() > 0
        && observed.compareTo(threshold.multiply(new BigDecimal("0.5"))) <= 0) {
      return FinanceFundAlertSeverity.CRITICAL.name();
    }
    return FinanceFundAlertSeverity.WARN.name();
  }

  private static FundAnalysisModels.FinanceFundLiquidityAlert alert(
      FinanceFundLiquidityRule rule,
      String severity,
      String currency,
      String title,
      String detail,
      String basis,
      BigDecimal observed,
      BigDecimal threshold,
      LocalDate from,
      LocalDate to) {
    return new FundAnalysisModels.FinanceFundLiquidityAlert(
        rule.name(),
        rule.label(),
        severity,
        currency,
        title,
        detail,
        basis,
        observed,
        threshold,
        from,
        to);
  }

  private static String percent(BigDecimal ratio) {
    return ratio.multiply(new BigDecimal("100")).setScale(2, RoundingMode.HALF_UP).toPlainString() + "%";
  }

  private static BigDecimal money(BigDecimal value) {
    return value == null ? BigDecimal.ZERO.setScale(MONEY_SCALE) : value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
  }

  private static String nextReference(String prefix) {
    return prefix + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
  }

  private static ResponseStatusException badRequest(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }
}
