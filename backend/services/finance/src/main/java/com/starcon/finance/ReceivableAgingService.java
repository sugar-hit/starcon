package com.starcon.finance;

import static com.starcon.finance.ReceivableAgingModels.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 12.1.1.2 应收账龄。
 *
 * <p>蓝图 12-finance.md：「按账龄区间（未到期/1-30/31-60/61-90/90+）统计应收分布，支持按店铺、客户、币种维度查看，识别逾期集中风险。」输入：「应收单集合；账龄区间配置。」输出：「账龄分布报表 + 逾期清单。」
 *
 * <p><b>本模块的五条口径纪律</b>（每一条都在下面有对应实现）：
 *
 * <ol>
 *   <li><b>只读上游，不写上游。</b>本模块读 12.1.1.1 的 {@code finance_receivable}（单头）与 {@code
 *       finance_receivable_line}（核销金额的真正出处），**一个字节都不往回写**。它的表只有一张自己的：{@code finance_aging_bucket}（账龄区间配置）。
 *   <li><b>不新建「已核销」的判断口径。</b>统计口径 = {@code status in ('OPEN','PARTIALLY_SETTLED')} 且金额取 {@code amount - settled_amount}（未核销余额）——
 *       这两个字段都是 V338 自己派生的（明细行的 {@code settled_amount} ⇒ 触发器派生单头），本模块**只读**。
 *       DRAFT（还没确认）、VOID（已作废）、SETTLED（已全额核销，未核销余额为 0）都不进报表。
 *   <li><b>币种分开。</b>USD 与 EUR 加起来不是任何一个金额 ⇒ 每一处汇总都按币种分组。
 *   <li><b>争议中金额单列。</b>跨境应收最大的风险是平台争议冻结 ⇒ 争议金额（{@code dispute_flag}）在每一行、每一个币种合计里**单独一列**，不混进总额。
 *   <li><b>汇总覆盖整个筛选结果集，不是当前页。</b>分页一变汇总就变的话，「翻到第二页看到总应收变小了」会被读成「数据丢了」。
 * </ol>
 *
 * <p><b>账龄基准日</b>：UTC 日历日。用会话时区的话，换一条连接就换一套分桶，分布会凭空多出空桶。
 *
 * <p><b>账龄区间配置</b>：租户自己维护（{@code finance_aging_bucket}）；没配过时生效的是**蓝图给定的五档**，响应里如实标出 {@code source}。
 * 详见 {@code V339__finance_receivable_aging.sql} 的头部说明。
 */
@Service
public class ReceivableAgingService {

  /** 一页最大条数。 */
  static final int MAX_PAGE_SIZE = 200;

  /** 蓝图给定的五档（租户没配过时生效的口径）。 */
  static final List<Bucket> BLUEPRINT_BUCKETS =
      List.of(
          new Bucket("NOT_DUE", "未到期", null, null, 0),
          new Bucket("D1_30", "逾期 1-30 天", 1, 30, 1),
          new Bucket("D31_60", "逾期 31-60 天", 31, 60, 2),
          new Bucket("D61_90", "逾期 61-90 天", 61, 90, 3),
          // ⚠️ 「90+」档从第 91 天起算：与 12.1.1.1 的 GET /finance/receivables/aging
          //   同一条边界（那边是 `<= 90 → 61-90 档、否则 90+ 档`）。两个模块必须同一条边界，
          //   否则同一个租户在两处会看到两套账龄。
          new Bucket("D90_PLUS", "逾期 90 天以上", 91, null, 4));

  /** CTE 的列清单：把「未核销余额」与「逾期天数」在**一处**算好，下游查询只做分组聚合。 */
  private static final String BASE_COLUMNS =
      "r.id, r.receivable_no, r.currency, r.due_date, r.amount, r.settled_amount,"
          + " r.dispute_flag, r.dispute_note, r.source, r.counterparty_type, r.counterparty_ref,"
          + " r.counterparty_name, r.shop_id, r.shop_name,"
          + " (r.amount - r.settled_amount) as open_amount,"
          + " (?::date - r.due_date) as overdue_days";

  /** 一个账龄区间（内部形态）。 */
  record Bucket(String code, String label, Integer from, Integer to, int sortOrder) {}

  /** 生效中的区间集合。 */
  record EffectiveBuckets(String source, boolean configured, List<Bucket> buckets) {}

  private final DSLContext sql;

  public ReceivableAgingService(DSLContext sql) {
    this.sql = sql;
  }

  // ================================================================ 能力声明

  /**
   * 能力声明：**每一维说清「有没有、依据是什么、缺了会怎样」**。
   *
   * <p>⚠️ 卷 12 的下游有两个「未交付」和两个「已交付但本模块没接」，必须分开说： 把它们都写成「不可用」，运维会以为要去催结算，而实际上核销是本服务自己的后续模块。
   */
  public FinanceReceivableAgingCapabilities capabilities() {
    var capabilities = new ArrayList<FinanceReceivableAgingCapability>();
    var gaps = new ArrayList<FinanceReceivableAgingDownstreamGap>();

    capabilities.add(
        new FinanceReceivableAgingCapability(
            "12.1.1.2",
            "应收账龄（账龄分布 + 逾期清单）",
            true,
            "finance_aging_bucket + finance_receivable（只读）",
            "",
            "按账龄区间统计未核销余额的分布，支持按店铺 / 客户 / 币种三个维度下钻，"
                + "并输出逾期清单。数据落 PostgreSQL 16，查询走 jOOQ 显式 SQL，"
                + "金额一律 BigDecimal + NUMERIC，无浮点。"));
    capabilities.add(
        new FinanceReceivableAgingCapability(
            "12.1.1.2",
            "账龄区间配置",
            true,
            "finance_aging_bucket",
            "",
            "账龄区间是**租户自己维护**的一份配置（蓝图的输入之一）。"
                + "没配过时生效的是**蓝图给定的五档**（未到期 / 1-30 / 31-60 / 61-90 / 90+），"
                + "响应里会标出 source = BLUEPRINT_DEFAULT —— 不会假装「这是你配的」。"
                + "档位必须连续覆盖 1 天起：留缝会让落在缝里的应收不属于任何一档（分布凭空少钱），"
                + "重叠会让后面的档永远命中不到。"));
    capabilities.add(
        new FinanceReceivableAgingCapability(
            "12.1.1.1",
            "应收单管理（上游）",
            true,
            "finance_receivable / finance_receivable_line（只读）",
            "",
            "账龄的数据来源：到期日、币种、未核销余额、争议标记都取自 12.1.1.1 的应收单。"
                + "本模块**只读**，不往单据上写任何东西。"));
    capabilities.add(
        new FinanceReceivableAgingCapability(
            "12.1.1.2",
            "争议中金额单列",
            true,
            "finance_receivable.dispute_flag（12.1.1.1 的人工标注）",
            "",
            "跨境应收最大的风险是平台争议冻结 ⇒ 争议金额在分布的每一行与每个币种合计里**单独一列**，"
                + "并单列「争议且已逾期」的部分。"
                + "⚠️ 争议事实来自 11.4.1.1 纠纷（trade 服务），当前**只支持人工标注，未自动联动**。"));
    capabilities.add(
        new FinanceReceivableAgingCapability(
            "12.2.1.2",
            "核销（收款核销到明细行）",
            false,
            "写路径未开放：行级 settled_amount 留好，触发器已把单头状态派生好",
            "12.2.1.2",
            "本模块统计的是**未核销余额**（amount - settled_amount），核销状态**只有明细行一个出处**。"
                + "核销一旦落地，账龄分布会**自动**跟着变小 —— 不需要改本模块的任何一列或一条 SQL。"));
    capabilities.add(
        new FinanceReceivableAgingCapability(
            "12.1.1.3",
            "坏账与核销（争议损失 / 拒付 / 冻结）",
            false,
            "审批链未交付",
            "12.1.1.3",
            "争议金额已单列进账龄分布，但**争议金额 ≠ 坏账**：坏账的确认与审批链属 12.1.1.3。"
                + "本模块**不**把争议金额当成已核销或已坏账处理。"));
    capabilities.add(
        new FinanceReceivableAgingCapability(
            "1.3.2.1",
            "催收预警（预警规则）",
            false,
            "下游属卷 01 前端聚合层，规则引擎本体在 18 系统",
            "1.3.2.1",
            "蓝图的「应收超期 Y 天」预警规则正是本模块的输出之一：账龄分布与逾期清单就是它要的输入。"
                + "本模块**只提供数据**，不生成通知、不写待办。"));

    gaps.add(
        new FinanceReceivableAgingDownstreamGap(
            "1.3.2.1", "预警规则（催收预警）", "web", false, "01 工作台与门户",
            "账龄分布与逾期清单已交付；预警规则的配置与命中记录属卷 01（规则引擎本体在 18 系统）。"));
    gaps.add(
        new FinanceReceivableAgingDownstreamGap(
            "12.1.1.3", "坏账与核销", "finance", false, "12.1 应收与资金",
            "争议金额已单列；坏账确认与审批链未交付。"));
    gaps.add(
        new FinanceReceivableAgingDownstreamGap(
            "12.2.1.2", "核销", "finance", false, "12.2 收付款",
            "核销写路径未开放；核销状态只有明细行一个出处。"));
    gaps.add(
        new FinanceReceivableAgingDownstreamGap(
            "11.4.1.1", "纠纷列表（争议事实来源）", "trade", false, "11 售后与退货",
            "争议当前**只支持人工标注**，与 11.4.1.1 的纠纷**未自动联动**。"));
    gaps.add(
        new FinanceReceivableAgingDownstreamGap(
            "13.1.1.1", "利润与经营分析", "analytics", false, "13 利润与经营分析",
            "13 未交付：账龄分布是它的一处上游输入。"));

    return new FinanceReceivableAgingCapabilities(List.copyOf(capabilities), List.copyOf(gaps));
  }

  // ================================================================ 筛选项

  /** 筛选项下拉：全部来自**真实数据**（distinct 查询），不是写死的词表。 */
  public FinanceReceivableAgingOptions options() {
    var cfg = effectiveBuckets();
    var currencies =
        sql.fetch(
                "select distinct currency from finance_receivable"
                    + " where status in ('OPEN','PARTIALLY_SETTLED') order by currency")
            .map(r -> r.get(0, String.class));
    var shops =
        sql.fetch(
                "select distinct shop_id, shop_name from finance_receivable"
                    + " where status in ('OPEN','PARTIALLY_SETTLED') and shop_id is not null"
                    + " order by shop_name, shop_id")
            .map(r -> new FinanceAgingOptionRef(r.get(0, UUID.class).toString(), r.get(1, String.class)));
    var counterparties =
        sql.fetch(
                "select distinct counterparty_ref, counterparty_name from finance_receivable"
                    + " where status in ('OPEN','PARTIALLY_SETTLED') and counterparty_ref is not null"
                    + " order by counterparty_name, counterparty_ref")
            .map(
                r ->
                    new FinanceAgingOptionRef(
                        r.get(0, UUID.class).toString(), r.get(1, String.class)));
    return new FinanceReceivableAgingOptions(
        currencies, shops, counterparties, defs(cfg), LocalDate.now(ZoneOffset.UTC));
  }

  // ================================================================ 账龄分布

  /**
   * 账龄分布报表：按维度下钻 × 账龄区间 × 币种。
   *
   * <p>⚠️ **按币种分开**：USD 与 EUR 加起来不是任何一个金额。 ⚠️ **争议中金额单列**：跨境应收最大的风险是平台争议冻结。
   */
  public FinanceReceivableAgingDistribution distribution(ReceivableAgingQuery query) {
    var cfg = effectiveBuckets();
    var dimension = dimensionOf(query.dimension());
    var asOf = LocalDate.now(ZoneOffset.UTC);
    var where = new ArrayList<String>();
    var binds = new ArrayList<Object>();
    where.add("r.status in ('OPEN','PARTIALLY_SETTLED')");
    applyFilters(query, where, binds);

    var cte = cte(asOf, where, binds);
    var bucketCase = bucketCase(cfg);
    var outerBinds = new ArrayList<Object>();
    var bucketClause = "";
    if (query.bucketCode() != null && !query.bucketCode().isBlank()) {
      bucketClause = " where " + bucketCase + " = ?";
      outerBinds.add(query.bucketCode().trim());
    }

    // 维度行（维度值 × 币种）
    var rows =
        sql.fetch(
                cte
                    + " select "
                    + dimensionKey(dimension)
                    + " as dimension_key,"
                    + dimensionName(dimension)
                    + " as dimension_name, base.currency,"
                    + " count(*) as cnt,"
                    + " sum(base.open_amount) as open_amount,"
                    + " coalesce(sum(base.open_amount) filter (where base.overdue_days > 0),0) as overdue_amount,"
                    + " coalesce(sum(base.open_amount) filter (where base.dispute_flag),0) as dispute_amount,"
                    + " coalesce(sum(base.open_amount) filter (where base.dispute_flag and base.overdue_days > 0),0) as dispute_overdue_amount"
                    + " from base"
                    + bucketClause
                    + " group by 1, 2, base.currency"
                    + " order by overdue_amount desc, dimension_name, base.currency",
                concat(binds, outerBinds))
            .map(
                r ->
                    new FinanceReceivableAgingRow(
                        dimension.name(),
                        r.get("dimension_key", String.class),
                        r.get("dimension_name", String.class),
                        r.get("currency", String.class),
                        r.get("cnt", Long.class),
                        money(r.get("open_amount", BigDecimal.class)),
                        money(r.get("overdue_amount", BigDecimal.class)),
                        money(r.get("dispute_amount", BigDecimal.class)),
                        money(r.get("dispute_overdue_amount", BigDecimal.class)),
                        null,
                        List.of()));

    // 维度行 × 区间（金额拆到每一档）
    var bucketRows =
        sql.fetch(
                cte
                    + " select "
                    + dimensionKey(dimension)
                    + " as dimension_key, base.currency, "
                    + bucketCase
                    + " as bucket, sum(base.open_amount) as amount, count(*) as cnt"
                    + " from base"
                    + bucketClause
                    + " group by 1, 2, 3",
                concat(binds, outerBinds));

    // 币种合计 + 每币种的区间分布
    var totals =
        sql.fetch(
                cte
                    + " select base.currency, count(*) as cnt, sum(base.open_amount) as open_amount,"
                    + " coalesce(sum(base.open_amount) filter (where base.overdue_days > 0),0) as overdue_amount,"
                    + " coalesce(sum(base.open_amount) filter (where base.dispute_flag),0) as dispute_amount,"
                    + " coalesce(sum(base.open_amount) filter (where base.dispute_flag and base.overdue_days > 0),0) as dispute_overdue_amount"
                    + " from base"
                    + bucketClause
                    + " group by base.currency order by base.currency",
                concat(binds, outerBinds));
    var currencyBuckets =
        sql.fetch(
                cte
                    + " select base.currency, "
                    + bucketCase
                    + " as bucket, sum(base.open_amount) as amount, count(*) as cnt"
                    + " from base"
                    + bucketClause
                    + " group by 1, 2",
                concat(binds, outerBinds));

    // 合并：维度行的区间明细
    var byDimension = new LinkedHashMap<String, List<FinanceAgingBucketAmount>>();
    for (var r : bucketRows) {
      var key = r.get("dimension_key", String.class) + "|" + r.get("currency", String.class);
      var bucket = r.get("bucket", String.class);
      byDimension
          .computeIfAbsent(key, k -> new ArrayList<>())
          .add(
              new FinanceAgingBucketAmount(
                  bucket,
                  labelOf(cfg, bucket),
                  money(r.get("amount", BigDecimal.class)),
                  r.get("cnt", Long.class)));
    }
    var currencyBucketMap = new LinkedHashMap<String, List<FinanceAgingBucketAmount>>();
    for (var r : currencyBuckets) {
      var key = r.get("currency", String.class);
      var bucket = r.get("bucket", String.class);
      currencyBucketMap
          .computeIfAbsent(key, k -> new ArrayList<>())
          .add(
              new FinanceAgingBucketAmount(
                  bucket,
                  labelOf(cfg, bucket),
                  money(r.get("amount", BigDecimal.class)),
                  r.get("cnt", Long.class)));
    }

    // 集中度：每个币种里逾期额最多的那个维度值（= 逾期集中风险的落点）。
    var topByCurrency = new LinkedHashMap<String, FinanceReceivableAgingRow>();
    for (var row : rows) {
      var current = topByCurrency.get(row.currency());
      if (current == null
          || (row.overdueAmount() != null
              && current.overdueAmount() != null
              && row.overdueAmount().compareTo(current.overdueAmount()) > 0)) {
        topByCurrency.put(row.currency(), row);
      }
    }

    // 每个币种的逾期总额（分母）—— 占比与集中度都按它算，**不跨币种**。
    var overdueByCurrency = new LinkedHashMap<String, BigDecimal>();
    for (var t : totals) {
      overdueByCurrency.put(
          t.get("currency", String.class), money(t.get("overdue_amount", BigDecimal.class)));
    }

    var mergedRows = new ArrayList<FinanceReceivableAgingRow>();
    for (var row : rows) {
      var key = row.dimensionKey() + "|" + row.currency();
      mergedRows.add(
          new FinanceReceivableAgingRow(
              row.dimension(),
              row.dimensionKey(),
              row.dimensionName(),
              row.currency(),
              row.count(),
              row.openAmount(),
              row.overdueAmount(),
              row.disputeAmount(),
              row.disputeOverdueAmount(),
              share(row.overdueAmount(), overdueByCurrency.get(row.currency())),
              sortedBuckets(cfg, byDimension.getOrDefault(key, List.of()))));
    }

    var mergedTotals = new ArrayList<FinanceAgingCurrencyTotal>();
    long totalCount = 0;
    for (var t : totals) {
      var currency = t.get("currency", String.class);
      var overdueAmount = money(t.get("overdue_amount", BigDecimal.class));
      var top = topByCurrency.get(currency);
      mergedTotals.add(
          new FinanceAgingCurrencyTotal(
              currency,
              t.get("cnt", Long.class),
              money(t.get("open_amount", BigDecimal.class)),
              overdueAmount,
              money(t.get("dispute_amount", BigDecimal.class)),
              money(t.get("dispute_overdue_amount", BigDecimal.class)),
              share(top == null ? null : top.overdueAmount(), overdueAmount),
              top == null ? null : top.dimensionKey(),
              top == null ? null : top.dimensionName(),
              sortedBuckets(cfg, currencyBucketMap.getOrDefault(currency, List.of()))));
      totalCount += t.get("cnt", Long.class);
    }

    return new FinanceReceivableAgingDistribution(
        asOf, cfg.source(), defs(cfg), dimension.name(), totalCount, mergedRows, mergedTotals);
  }

  // ================================================================ 逾期清单

  /**
   * 逾期清单：真分页 + 真筛选 + 真排序。
   *
   * <p>⚠️ **汇总覆盖的是整个筛选结果集，不是当前页**：分页一变汇总就变的话，「翻到第二页看到总逾期变小了」会被读成「数据丢了」。
   */
  public FinanceReceivableAgingOverduePage overdue(ReceivableAgingQuery query) {
    var cfg = effectiveBuckets();
    var asOf = LocalDate.now(ZoneOffset.UTC);
    int p = Math.max(0, query.page());
    int s = Math.min(MAX_PAGE_SIZE, Math.max(1, query.size()));

    var where = new ArrayList<String>();
    var binds = new ArrayList<Object>();
    where.add("r.status in ('OPEN','PARTIALLY_SETTLED')");
    applyFilters(query, where, binds);

    var cte = cte(asOf, where, binds);
    var bucketCase = bucketCase(cfg);
    var outerBinds = new ArrayList<Object>();
    var bucketClause = "";
    if (query.bucketCode() != null && !query.bucketCode().isBlank()) {
      bucketClause = " and " + bucketCase + " = ?";
      outerBinds.add(query.bucketCode().trim());
    }
    var overdueClause = " where base.overdue_days > 0" + bucketClause;

    long total =
        sql.fetchOne(cte + " select count(*) from base" + overdueClause, concat(binds, outerBinds))
            .get(0, Long.class);

    var items =
        sql.fetch(
                cte
                    + " select base.id, base.receivable_no, base.currency, base.counterparty_type,"
                    + " base.counterparty_ref, base.counterparty_name, base.shop_id, base.shop_name,"
                    + " base.due_date, base.overdue_days, "
                    + bucketCase
                    + " as bucket, base.amount, base.settled_amount, base.open_amount,"
                    + " base.dispute_flag, base.dispute_note, base.source"
                    + " from base"
                    + overdueClause
                    + " order by base.overdue_days desc, base.open_amount desc, base.id"
                    + " limit ? offset ?",
                concat(binds, outerBinds, s, p * s))
            .map(
                r ->
                    new FinanceReceivableAgingOverdueRow(
                        r.get("id", UUID.class),
                        r.get("receivable_no", String.class),
                        r.get("currency", String.class),
                        r.get("counterparty_type", String.class),
                        r.get("counterparty_ref", UUID.class),
                        r.get("counterparty_name", String.class),
                        r.get("shop_id", UUID.class),
                        r.get("shop_name", String.class),
                        r.get("due_date", LocalDate.class),
                        r.get("overdue_days", Long.class),
                        r.get("bucket", String.class),
                        labelOf(cfg, r.get("bucket", String.class)),
                        money(r.get("amount", BigDecimal.class)),
                        money(r.get("settled_amount", BigDecimal.class)),
                        money(r.get("open_amount", BigDecimal.class)),
                        r.get("dispute_flag", Boolean.class),
                        r.get("dispute_note", String.class),
                        r.get("source", String.class)));

    var summaries =
        sql.fetch(
                cte
                    + " select base.currency, count(*) as cnt, sum(base.open_amount) as open_amount,"
                    + " coalesce(sum(base.open_amount) filter (where base.dispute_flag),0) as dispute_amount,"
                    + " count(*) filter (where base.dispute_flag) as dispute_count"
                    + " from base"
                    + overdueClause
                    + " group by base.currency order by base.currency",
                concat(binds, outerBinds))
            .map(
                r ->
                    new FinanceAgingCurrencySummary(
                        r.get("currency", String.class),
                        r.get("cnt", Long.class),
                        money(r.get("open_amount", BigDecimal.class)),
                        money(r.get("open_amount", BigDecimal.class)),
                        money(r.get("dispute_amount", BigDecimal.class)),
                        r.get("dispute_count", Long.class)));

    long disputeCount =
        summaries.stream().mapToLong(FinanceAgingCurrencySummary::disputeCount).sum();
    return new FinanceReceivableAgingOverduePage(
        items, p, s, total, asOf, summaries, disputeCount);
  }

  // ================================================================ 账龄区间配置

  /** 生效中的区间配置（租户配的，或蓝图默认的）。 */
  public FinanceReceivableAgingBucketConfig bucketConfig() {
    var cfg = effectiveBuckets();
    return new FinanceReceivableAgingBucketConfig(
        cfg.source(),
        cfg.configured(),
        defs(cfg),
        cfg.configured()
            ? "当前生效的是**租户自己配置**的账龄区间（改配置立即生效，下一次报表按新区间分档）。"
            : "当前生效的是**蓝图给定的五档**（未到期 / 1-30 / 31-60 / 61-90 / 90+）。"
                + "账龄区间是租户自己的口径 —— 配置一份自己的，报表就按你的分档。");
  }

  /**
   * 配置账龄区间（**整体替换**）。
   *
   * <p>⚠️ 为什么是整体替换：档位之间必须连续覆盖 1 天起，这条约束只有对着整份配置才判得出来。逐档写会出现「改到一半的中间态」，那种状态下落在缝里的应收不属于任何一档 —— 报表会凭空少钱。整体替换 ⇒ 配置要么合法要么不落库。
   *
   * <p>空列表 = 回到**蓝图给定的五档**（不是「清空后什么都不算」）。
   */
  @Transactional
  public FinanceReceivableAgingBucketConfig updateBuckets(
      FinanceAgingBucketConfigInput input, AccessService.Access actor) {
    var items = input == null || input.buckets() == null ? List.<FinanceAgingBucketInput>of() : input.buckets();
    if (items.isEmpty()) {
      sql.execute("delete from finance_aging_bucket");
      return bucketConfig();
    }
    if (items.size() > 32) {
      throw badRequest("账龄区间最多 32 档（当前 " + items.size() + " 档）");
    }
    var codes = new HashSet<String>();
    var buckets = new ArrayList<Bucket>();
    for (var item : items) {
      var code = item.code() == null ? "" : item.code().trim();
      if (!code.matches("^[A-Z][A-Z0-9_]{0,31}$")) {
        throw badRequest("账龄区间编码只能是大写字母、数字与下划线，且必须以字母开头：" + code);
      }
      if (!codes.add(code)) {
        throw badRequest("账龄区间编码重复：" + code);
      }
      var label = item.label() == null ? "" : item.label().trim();
      if (label.isEmpty()) {
        throw badRequest("账龄区间必须写明名称：" + code);
      }
      var from = item.overdueFromDays();
      var to = item.overdueToDays();
      if (from == null && to != null) {
        throw badRequest("账龄区间「" + label + "」只有上界没有下界，不合法");
      }
      if (from != null && from < 1) {
        throw badRequest("逾期天数下界必须 >= 1（未到期档请把下界留空）：" + label);
      }
      if (from != null && to != null && to < from) {
        throw badRequest("账龄区间的上界不能小于下界：" + label);
      }
      buckets.add(
          new Bucket(code, label, from, to, item.sortOrder() == null ? buckets.size() : item.sortOrder()));
    }
    var problems = bucketProblems(buckets);
    if (!problems.isEmpty()) {
      throw badRequest("账龄区间配置不合法：" + String.join("；", problems));
    }
    sql.execute("delete from finance_aging_bucket");
    var at = OffsetDateTime.now(ZoneOffset.UTC);
    for (var b : buckets) {
      sql.execute(
          "insert into finance_aging_bucket(id,tenant_id,code,label,overdue_from_days,overdue_to_days,"
              + " sort_order,created_at,updated_at)"
              + " values(?,?,?,?,?,?,?,?::timestamptz,?::timestamptz)",
          UUID.randomUUID(),
          actor.tenantId(),
          b.code(),
          b.label(),
          b.from(),
          b.to(),
          b.sortOrder(),
          at,
          at);
    }
    return bucketConfig();
  }

  // ================================================================ 内部

  /** 生效中的区间：租户配的优先，没配过用蓝图五档。 */
  private EffectiveBuckets effectiveBuckets() {
    var rows =
        sql.fetch(
            "select code,label,overdue_from_days,overdue_to_days,sort_order"
                + " from finance_aging_bucket order by sort_order, code");
    if (rows.isEmpty()) {
      return new EffectiveBuckets("BLUEPRINT_DEFAULT", false, BLUEPRINT_BUCKETS);
    }
    var buckets = new ArrayList<Bucket>();
    for (var r : rows) {
      var code = r.get("code", String.class);
      // 编码会被拼进 CASE 分支当字面量用 —— 不合法就直接挡住，绝不拼进 SQL。
      if (code == null || !code.matches("^[A-Z][A-Z0-9_]{0,31}$")) {
        throw new ResponseStatusException(
            HttpStatus.INTERNAL_SERVER_ERROR, "账龄区间配置里的编码不合法：" + code);
      }
      buckets.add(
          new Bucket(
              code,
              r.get("label", String.class),
              r.get("overdue_from_days", Integer.class),
              r.get("overdue_to_days", Integer.class),
              r.get("sort_order", Integer.class)));
    }
    var problems = bucketProblems(buckets);
    if (!problems.isEmpty()) {
      // 配置是服务层写进来的；能被读成不合法，说明有人绕过了服务层。
      // 此时**不能**算出一张错的报表 —— 直接报出来，让人去修配置。
      throw new ResponseStatusException(
          HttpStatus.INTERNAL_SERVER_ERROR,
          "账龄区间配置不合法，报表算不出来（请重新配置账龄区间）：" + String.join("；", problems));
    }
    return new EffectiveBuckets("TENANT_CONFIG", true, List.copyOf(buckets));
  }

  /**
   * 档位合法性检查。
   *
   * <p>规则（全部来自「报表不能算错」这一个目标）：
   *
   * <ol>
   *   <li>恰好一个「未到期」档 —— 两个的话必有一个永远命中不到；
   *   <li>逾期档的下界必须 >= 1（0 天是「今天到期」，属于未到期）；
   *   <li>逾期档按下界排序后必须**连续覆盖 1 天起**（留缝 ⇒ 缝里的钱不属于任何一档）；
   *   <li>必须恰好有一档**无上界**（否则逾期更久的应收落不进任何一档），且它必须是最后一档。
   * </ol>
   */
  static List<String> bucketProblems(List<Bucket> buckets) {
    var problems = new ArrayList<String>();
    long notDue = buckets.stream().filter(b -> b.from() == null).count();
    if (notDue != 1) {
      problems.add("必须恰好有一个「未到期」档（当前 " + notDue + " 个）");
    }
    var tiers =
        buckets.stream()
            .filter(b -> b.from() != null)
            .sorted(Comparator.comparingInt(Bucket::from))
            .toList();
    int expected = 1;
    boolean unboundedSeen = false;
    for (int i = 0; i < tiers.size(); i++) {
      var tier = tiers.get(i);
      if (tier.from() != expected) {
        problems.add(
            "逾期档的天数区间必须连续：「"
                + tier.label()
                + "」从第 "
                + tier.from()
                + " 天起，期望从第 "
                + expected
                + " 天起（留缝会让落在缝里的应收不属于任何一档）");
        break;
      }
      if (tier.to() == null) {
        if (i != tiers.size() - 1) {
          problems.add("无上界的档必须是最后一档：「" + tier.label() + "」后面还有别的档");
        }
        unboundedSeen = true;
        break;
      }
      expected = tier.to() + 1;
    }
    if (!unboundedSeen && tiers.isEmpty()) {
      problems.add("至少要有一个逾期档（如「逾期 1-30 天」）");
    } else if (!unboundedSeen) {
      problems.add("必须有一档没有上界（如「逾期 90 天以上」），否则逾期更久的应收落不进任何一档");
    }
    return problems;
  }

  /** 生效区间 → 对外模型（按 sort_order 排）。 */
  private List<FinanceAgingBucketDef> defs(EffectiveBuckets cfg) {
    return cfg.buckets().stream()
        .sorted(Comparator.comparingInt(Bucket::sortOrder).thenComparing(Bucket::code))
        .map(b -> new FinanceAgingBucketDef(b.code(), b.label(), b.from(), b.to(), b.sortOrder()))
        .toList();
  }

  /** 区间编码 → 名称（报表里每行都要带名称，页面不自己猜）。 */
  private String labelOf(EffectiveBuckets cfg, String code) {
    for (var b : cfg.buckets()) {
      if (b.code().equals(code)) return b.label();
    }
    return code;
  }

  /** 区间金额列表按生效顺序排（不是按编码字母序）。 */
  private List<FinanceAgingBucketAmount> sortedBuckets(
      EffectiveBuckets cfg, List<FinanceAgingBucketAmount> buckets) {
    var order = new LinkedHashMap<String, Integer>();
    var i = 0;
    for (var b : cfg.buckets()) order.put(b.code(), i++);
    var out = new ArrayList<>(buckets);
    out.sort(Comparator.comparingInt(b -> order.getOrDefault(b.code(), Integer.MAX_VALUE)));
    return out;
  }

  /**
   * 分桶表达式（CASE）。
   *
   * <p>⚠️ 档位区间**连续**（服务层校验过），所以「小于等于上界」的写法就够用：第 1 档 1-30、第 2 档 31-60 …… 命中顺序即区间顺序。
   *
   * <p>⚠️ 区间编码来自 DB（受 CHECK 与服务层双重校验，只含大写字母 / 数字 / 下划线），拼进字面量是安全的；不合法的编码在 {@link #effectiveBuckets()} 里就被挡住了。
   */
  private String bucketCase(EffectiveBuckets cfg) {
    var ordered = ordered(cfg);
    var sb = new StringBuilder("case");
    for (var b : ordered) {
      if (b.from() == null) {
        sb.append(" when base.overdue_days <= 0 then '").append(b.code()).append("'");
      } else if (b.to() != null) {
        sb.append(" when base.overdue_days <= ").append(b.to()).append(" then '").append(b.code()).append("'");
      }
    }
    sb.append(" else '").append(unboundedCode(cfg)).append("' end");
    return sb.toString();
  }

  /** 未到期档在前、逾期档按下界升序。 */
  private List<Bucket> ordered(EffectiveBuckets cfg) {
    var out = new ArrayList<Bucket>();
    cfg.buckets().stream().filter(b -> b.from() == null).forEach(out::add);
    cfg.buckets().stream()
        .filter(b -> b.from() != null)
        .sorted(Comparator.comparingInt(Bucket::from))
        .forEach(out::add);
    return out;
  }

  /** 无上界那一档的编码（合法配置里恰好一个）。 */
  private String unboundedCode(EffectiveBuckets cfg) {
    for (var b : cfg.buckets()) {
      if (b.from() != null && b.to() == null) return b.code();
    }
    // 合法配置里到不了这里；真到了，返回第一个逾期档，让 CASE 仍然有 else 分支。
    return cfg.buckets().stream()
        .filter(b -> b.from() != null)
        .findFirst()
        .map(b -> b.code())
        .orElse("UNKNOWN");
  }

  /** CTE：把「未核销余额」与「逾期天数」在一处算好。 */
  private String cte(LocalDate asOf, List<String> where, List<Object> binds) {
    binds.add(0, asOf);
    return "with base as (select " + BASE_COLUMNS + " from finance_receivable r where "
        + String.join(" and ", where)
        + ")";
  }

  private void applyFilters(ReceivableAgingQuery q, List<String> where, List<Object> binds) {
    if (q.currency() != null && !q.currency().isBlank()) {
      where.add("r.currency = ?");
      binds.add(q.currency().trim().toUpperCase());
    }
    if (q.shopId() != null) {
      where.add("r.shop_id = ?");
      binds.add(q.shopId());
    }
    if (q.counterpartyRef() != null) {
      where.add("r.counterparty_ref = ?");
      binds.add(q.counterpartyRef());
    }
    if (q.disputeOnly() != null && q.disputeOnly()) {
      where.add("r.dispute_flag");
    }
  }

  private String dimensionKey(AgingDimension dimension) {
    return switch (dimension) {
      case SHOP -> "coalesce(base.shop_id::text, '')";
      case COUNTERPARTY -> "coalesce(base.counterparty_ref::text, base.counterparty_name)";
      case CURRENCY -> "base.currency";
    };
  }

  private String dimensionName(AgingDimension dimension) {
    return switch (dimension) {
      case SHOP -> "coalesce(nullif(base.shop_name, ''), '未指定店铺')";
      case COUNTERPARTY -> "coalesce(nullif(base.counterparty_name, ''), '未指定应收对象')";
      case CURRENCY -> "base.currency";
    };
  }

  private AgingDimension dimensionOf(String raw) {
    if (raw == null || raw.isBlank()) return AgingDimension.SHOP;
    try {
      return AgingDimension.valueOf(raw.trim().toUpperCase());
    } catch (IllegalArgumentException e) {
      throw badRequest("账龄维度取值不合法：" + raw + "（可用 SHOP / COUNTERPARTY / CURRENCY）");
    }
  }

  /**
   * 占比：分母为 0 时给 {@code null} 而不是 0。
   *
   * <p>「没有逾期」与「占比 0」必须能分开 —— 前者是事实，后者是算术结果。
   */
  private BigDecimal share(BigDecimal part, BigDecimal whole) {
    if (part == null || whole == null || whole.signum() == 0) return null;
    return part.divide(whole, RATIO_SCALE, RoundingMode.HALF_UP);
  }

  private static BigDecimal money(BigDecimal value) {
    return value == null ? null : value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
  }

  private static ResponseStatusException badRequest(String detail) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, detail);
  }

  private static Object[] concat(List<Object> binds, List<Object> more) {
    var out = new Object[binds.size() + more.size()];
    for (int i = 0; i < binds.size(); i++) out[i] = binds.get(i);
    for (int i = 0; i < more.size(); i++) out[binds.size() + i] = more.get(i);
    return out;
  }

  private static Object[] concat(List<Object> binds, List<Object> more, Object... tail) {
    var head = concat(binds, more);
    var out = new Object[head.length + tail.length];
    System.arraycopy(head, 0, out, 0, head.length);
    System.arraycopy(tail, 0, out, head.length, tail.length);
    return out;
  }
}
