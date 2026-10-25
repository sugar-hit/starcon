package com.starcon.reporting;

import com.starcon.tenancy.TenantTransactions;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Service;

/**
 * 13.4.2.2 费用差异的**读模型**（蓝图「输入」句：测算费用（5.2.3）；实际费用（12.3.1.2）；
 * 时间范围）。
 *
 * <p>⚠️ **本类不重算口径**：预计费用来自上游规则测算（已落成
 * {@code reporting_expense_estimate_fact}），实际费用来自上游事实（已落成
 * {@code reporting_expense_actual_fact}）。本类只做**对照与算术**。
 *
 * <p>⚠️ **差异 = 实际 − 预计**（这个符号口径写进响应里、也写进页面上）：
 * 差异为**负** ⇒ 实际比预计少 ⇒ <b>预计高估</b>了 ⇒ 费用规则配高了；
 * 差异为**正** ⇒ 实际比预计多 ⇒ <b>预计低估</b>了 ⇒ 费用规则配低了。
 * 蓝图那句「**持续为负的差异说明费用规则配错了**」就是在这个口径下成立的。
 *
 * <p>⚠️ **差异率一律用 BigDecimal 显式定标计算**：分母缺失或为 0 时差异率是
 * {@code null}（**不可比**），**不是 0%**。
 *
 * <p>⚠️ **不跨币种相加**：每一行都带币种，跨币种合计不存在。
 */
@Service
public class ReportingExpenseService {

  /** 触发规则调优建议的差异率阈值（百分数）：|差异率| ≥ 5% 才算「系统性偏差」。 */
  static final BigDecimal RECOMMENDATION_THRESHOLD = new BigDecimal("5.0000");

  /** 触发规则调优建议所需的最少可比样本笔数（样本太少时「持续偏差」说不成立）。 */
  static final int RECOMMENDATION_MIN_SAMPLES = 3;

  /** 差异表最多返回多少行（超出时如实报截断，不静默丢行）。 */
  static final int MAX_ROWS = 500;

  /** 差异率的标度（百分数，保留 4 位 —— 与既有指标口径一致）。 */
  private static final int RATE_SCALE = 4;

  private final DSLContext sql;
  private final TenantTransactions tenants;

  public ReportingExpenseService(DSLContext sql, TenantTransactions tenants) {
    this.sql = sql;
    this.tenants = tenants;
  }

  // ==========================================================================
  // 差异表 + 趋势 + 建议
  // ==========================================================================

  /**
   * 费用差异：差异表 + 有实际无预计 + 偏差趋势 + 规则调优建议 + 数据更新时间。
   *
   * @param granularity 期间粒度：{@code DAY} / {@code WEEK} / {@code MONTH}
   */
  public ReportingExpenseModels.ExpenseVarianceResponse variance(
      UUID tenantId,
      LocalDate from,
      LocalDate to,
      String granularity,
      String feeType,
      String platformCode,
      String shopCode,
      String skuCode) {
    return tenants.run(
        tenantId,
        () -> {
          var estimates = estimateGroups(tenantId, from, to, feeType, platformCode, shopCode, skuCode);
          var actuals = actualGroups(tenantId, from, to, feeType, platformCode, shopCode, skuCode);

          var rows = new ArrayList<ReportingExpenseModels.ExpenseVarianceRow>();
          var unmatched = new ArrayList<ReportingExpenseModels.UnmatchedActualRow>();
          var matchedActuals = new java.util.HashSet<String>();

          for (var group : estimates) {
            var actual = sumActualsWithin(actuals, group);
            matchedActuals.add(group.actualKey());
            var estimatedAmount = group.amounts().amount();
            var actualAmount = actual.amount();
            var varianceAmount =
                (estimatedAmount == null || actualAmount == null)
                    ? null
                    : actualAmount.subtract(estimatedAmount);
            var rate =
                (varianceAmount == null || estimatedAmount == null || estimatedAmount.signum() == 0)
                    ? null
                    : varianceAmount
                        .multiply(new BigDecimal("100"))
                        .divide(estimatedAmount, 8, RoundingMode.HALF_UP)
                        .setScale(RATE_SCALE, RoundingMode.HALF_UP);
            var comparable = estimatedAmount != null && actualAmount != null;
            rows.add(
                new ReportingExpenseModels.ExpenseVarianceRow(
                    group.feeType(),
                    label(group.feeType()),
                    group.feeSubType(),
                    group.periodStart(),
                    group.periodEnd(),
                    group.currency(),
                    estimatedAmount,
                    actualAmount,
                    varianceAmount,
                    rate,
                    comparable,
                    group.amounts().count(),
                    actual.count(),
                    group.amounts().base(),
                    group.platformCode(),
                    group.siteCode(),
                    group.channelCode(),
                    group.carrierCode(),
                    group.shippingMethodCode(),
                    group.shopCode(),
                    group.skuCode(),
                    scopeLabel(group),
                    actual.sources(),
                    comparable ? "预计与实际都取得到，可比。" : "只有一侧取得到，差异算不出来（不是 0）。"));
          }

          for (var group : actuals) {
            if (matchedActuals.contains(group.actualKey())) continue;
            if (coveredByEstimate(estimates, group)) continue;
            unmatched.add(
                new ReportingExpenseModels.UnmatchedActualRow(
                    group.feeType(),
                    label(group.feeType()),
                    group.feeSubType(),
                    group.currency(),
                    group.amounts().amount(),
                    group.amounts().count(),
                    scopeLabel(group),
                    group.amounts().sources(),
                    unmatchedReason(group)));
          }

          // ⚠️ 行数超上限时**如实报截断**（不静默丢行）。
          var truncated = rows.size() > MAX_ROWS;
          if (truncated) {
            rows = new ArrayList<>(rows.subList(0, MAX_ROWS));
          }

          var trend = trend(rows, granularity);
          var recommendations = recommendations(rows, from, to);
          var dataUpdatedAt = dataUpdatedAt(tenantId);

          var warnings = new ArrayList<String>();
          if (truncated) {
            warnings.add("差异表行数超过 " + MAX_ROWS + " 行，已截断（页面只显示前 " + MAX_ROWS + " 行）。");
          }
          if (!unmatched.isEmpty()) {
            warnings.add(
                "有 " + unmatched.size() + " 组实际费用没有可比的预计费用，已单列在「有实际、无预计」里 —— 差异率算不出来，不是 0%。");
          }
          warnings.add("报表是投影，数据更新时间取自投影落库时刻；与业务系统冲突时以业务系统为准。");

          return new ReportingExpenseModels.ExpenseVarianceResponse(
              from,
              to,
              granularity,
              rows,
              unmatched,
              trend,
              recommendations,
              dataUpdatedAt,
              "最近一次投影落库时刻（预计 / 实际两张事实表 updated_at 取较晚者）。",
              "差异 = 实际 − 预计：差异为负 ⇒ 预计高估（规则配高了）；差异为正 ⇒ 预计低估（规则配低了）。",
              warnings,
              caliberNote());
        });
  }

  // ==========================================================================
  // SKU 级差异（蓝图职责句点名的「按 SKU 与期间展示差异率」）
  // ==========================================================================

  /** SKU 级差异（真分页 + 真计数）。 */
  public ReportingExpenseModels.ExpenseSkuVariancePage skuVariance(
      UUID tenantId, LocalDate from, LocalDate to, String feeType, int page, int size) {
    return tenants.run(
        tenantId,
        () -> {
          var feeFilter = feeType == null || feeType.isBlank() ? "" : " and fee_type = ?";

          // ⚠️ 计数与明细**必须同一个分组口径**（否则 total 与 items 对不上）：
          //   两侧都按 SKU × 费用类型 × 子类型 × 币种 × 期间分组。
          var countParams = new ArrayList<Object>();
          countParams.add(tenantId);
          countParams.add(from);
          countParams.add(to);
          if (feeType != null && !feeType.isBlank()) countParams.add(feeType);
          var total =
              sql.fetchOne(
                  """
                  select count(*) from (
                    select 1 from reporting_expense_estimate_fact
                     where tenant_id = ? and sku_code <> ''
                       and period_end >= ? and period_start <= ?
                  """
                      + feeFilter
                      + """
                     group by sku_code, fee_type, fee_sub_type, currency, period_start, period_end
                  ) t
                  """,
                  countParams.toArray());

          // ⚠️ **预计侧与实际侧先各自聚合、再按期间窗口对照**：
          //   实际费用按**日**聚合（落进预计费用的期间窗口内的那几天），
          //   预计费用按**期间**聚合（逐日事实两列相同，窗口型事实 period_end > period_start）。
          //   ⚠️ 早先的写法是把实际侧写成外层查询的**相关子查询**并引用外层的维度列 ——
          //   那些列不在 GROUP BY 里 ⇒ PostgreSQL 直接拒绝
          //   （subquery uses ungrouped column）。改成 CTE 后子查询只引用已分组的列。
          var rows =
              sql.fetch(
                  """
                  with est as (
                    select sku_code, fee_type, fee_sub_type, currency, period_start, period_end,
                           sum(estimated_amount) as estimated,
                           coalesce(sum(base_amount), 0) as base,
                           count(*) as cnt
                      from reporting_expense_estimate_fact
                     where tenant_id = ? and sku_code <> ''
                       and period_end >= ? and period_start <= ?
                  """
                      + feeFilter
                      + """
                     group by sku_code, fee_type, fee_sub_type, currency, period_start, period_end
                  ), act as (
                    select sku_code, fee_type, fee_sub_type, currency, period_start as day,
                           sum(actual_amount) as actual
                      from reporting_expense_actual_fact
                     where tenant_id = ? and sku_code <> ''
                       and period_end >= ? and period_start <= ?
                     group by sku_code, fee_type, fee_sub_type, currency, period_start
                  )
                  select est.sku_code, est.fee_type, est.fee_sub_type, est.currency,
                         est.period_start, est.period_end,
                         est.estimated, est.base, est.cnt,
                         coalesce((select sum(act.actual)
                                     from act
                                    where act.sku_code = est.sku_code
                                      and act.fee_type = est.fee_type
                                      and act.fee_sub_type = est.fee_sub_type
                                      and act.currency = est.currency
                                      and act.day >= est.period_start
                                      and act.day <= est.period_end), 0) as actual
                    from est
                   order by est.sku_code, est.fee_type, est.period_start
                   limit ? offset ?
                  """,
                  append(
                      rowParams(tenantId, from, to, feeType), size, Math.max(page, 0) * size));

          var items = new ArrayList<ReportingExpenseModels.ExpenseSkuVarianceRow>();
          for (var row : rows) {
            var estimated = row.get(6, BigDecimal.class);
            var actual = row.get(9, BigDecimal.class);
            var varianceAmount =
                (estimated == null || actual == null) ? null : actual.subtract(estimated);
            var rate =
                (varianceAmount == null || estimated == null || estimated.signum() == 0)
                    ? null
                    : varianceAmount
                        .multiply(new BigDecimal("100"))
                        .divide(estimated, 8, RoundingMode.HALF_UP)
                        .setScale(RATE_SCALE, RoundingMode.HALF_UP);
            items.add(
                new ReportingExpenseModels.ExpenseSkuVarianceRow(
                    row.get(0, String.class),
                    row.get(1, String.class),
                    label(row.get(1, String.class)),
                    row.get(2, String.class),
                    row.get(3, LocalDate.class),
                    row.get(4, LocalDate.class),
                    row.get(5, String.class),
                    estimated,
                    actual,
                    varianceAmount,
                    rate,
                    estimated != null && actual != null,
                    row.get(7, Long.class) == null ? 0L : row.get(7, Long.class),
                    "SKU " + row.get(0, String.class),
                    estimated != null && actual != null
                        ? "预计与实际都取得到，可比。"
                        : "只有一侧取得到，差异算不出来（不是 0）。"));
          }
          return new ReportingExpenseModels.ExpenseSkuVariancePage(
              items,
              Math.max(page, 0),
              size,
              total == null ? 0L : total.get(0, Long.class),
              "SKU 级差异只覆盖**两侧都有事实**的 SKU；只有一侧的归在「有实际、无预计」里。");
        });
  }

  // ==========================================================================
  // 能力声明
  // ==========================================================================

  /** 能力声明：哪些上游真接通、哪些确实拿不到、归谁。 */
  public ReportingExpenseModels.ExpenseVarianceCapabilities capabilities() {
    return new ReportingExpenseModels.ExpenseVarianceCapabilities(
        true,
        List.of(
            ReportingExpenseModels.FeeType.PLATFORM_FEE.label(),
            ReportingExpenseModels.FeeType.LOGISTICS_FEE.label(),
            ReportingExpenseModels.FeeType.AD_FEE.label(),
            ReportingExpenseModels.FeeType.HEAD_FREIGHT.label()),
        List.of(
            "5.2.3.1 平台费用（catalog：实际费用台账 + 按规则测算）",
            "5.2.3.2 物流与头程费用（catalog：实际运费台账 + 按规则测算）",
            "5.2.1.1 成本构成（catalog：FIRST_MILE 头程分摊分项）",
            "5.2.1.3 成本分摊（catalog：逐 SKU 头程分摊额）",
            "12.3.1.2 交易明细（finance：佣金 / FBA 费用 / 广告费）",
            "14 广告预算分配方案（channel：广告费的预计侧）"),
        List.of(
            "**平台费与物流费的 SKU 级差异算不出来**：上游的实际费用台账"
                + "（platform_fee_actual / logistics_freight_actual）只带平台 / 站点 / 渠道维度，"
                + "**不带 SKU**，而结算明细只到订单级 ⇒ 这两类费用只能按平台 / 渠道维度对照。"
                + "SKU 级差异目前只有**头程分摊**有（两侧都带 sku_code）。",
            "**12.3.1.2 结算明细那一侧没有预计值**：结算明细只给费用金额，"
                + "**不带销售额 / 数量基数**，而比例规则与按件规则重算预计值必须有基数 ⇒ "
                + "这些行落在「有实际、无预计」里，差异率算不出来（不是 0%）。",
            "**广告费没有费用规则**：5.2.3 交付的是平台费与物流头程费的规则，"
                + "广告那边交付的是**预算分配方案** ⇒ 广告费的预计侧取预算分配额，"
                + "实际侧取 12.3.1.2 的广告费明细；两者口径不同源，比较的是「预算 vs 实际」。",
            "**头程分摊的实际侧不含人工微调**：微调恰恰是运营**已经**对分摊偏差做的修正，"
                + "算进来会把这个模块要找的系统性偏差抹掉。",
            "**上游的推事件**：上游尚未产出费用事实型 outbox 事件 ⇒ 现在的事实来自 "
                + "POST /reporting/projection/refresh 的拉取路径（推路径已就绪，与拉共用同一套幂等键与重建逻辑）。"),
        RECOMMENDATION_THRESHOLD,
        RECOMMENDATION_MIN_SAMPLES,
        "预计费用由上游规则算出来（catalog 的 /estimate），本服务**不重算费率** —— "
            + "重算就会出现第二个真相。");
  }

  // ==========================================================================
  // 取数
  // ==========================================================================

  /** 一组聚合后的行（预计侧或实际侧）。 */
  private record Group(
      String feeType,
      String feeSubType,
      String platformCode,
      String siteCode,
      String channelCode,
      String carrierCode,
      String shippingMethodCode,
      String shopCode,
      String skuCode,
      String currency,
      LocalDate periodStart,
      LocalDate periodEnd,
      Amounts amounts) {

    /** 与实际侧对齐用的键（**不含期间**：期间是「实际落在哪一段」的判据）。 */
    String actualKey() {
      return String.join(
          "",
          feeType,
          feeSubType,
          platformCode,
          siteCode,
          channelCode,
          carrierCode,
          shippingMethodCode,
          shopCode,
          skuCode,
          currency);
    }
  }

  /** 聚合出来的金额。 */
  private record Amounts(
      BigDecimal amount, BigDecimal base, long count, String sources) {}

  /** 预计侧的聚合结果。 */
  private List<Group> estimateGroups(
      UUID tenantId,
      LocalDate from,
      LocalDate to,
      String feeType,
      String platformCode,
      String shopCode,
      String skuCode) {
    var params = new ArrayList<Object>();
    params.add(tenantId);
    params.add(from);
    params.add(to);
    var filters = new StringBuilder();
    if (feeType != null && !feeType.isBlank()) {
      filters.append(" and fee_type = ?");
      params.add(feeType);
    }
    if (platformCode != null && !platformCode.isBlank()) {
      filters.append(" and platform_code = ?");
      params.add(platformCode);
    }
    if (shopCode != null && !shopCode.isBlank()) {
      filters.append(" and shop_code = ?");
      params.add(shopCode);
    }
    if (skuCode != null && !skuCode.isBlank()) {
      filters.append(" and sku_code = ?");
      params.add(skuCode);
    }
    var rows =
        sql.fetch(
            """
            select fee_type, fee_sub_type, platform_code, site_code, channel_code, carrier_code,
                   shipping_method_code, shop_code, sku_code, currency, period_start, period_end,
                   sum(estimated_amount) as amount, coalesce(sum(base_amount), 0) as base, count(*) as cnt
              from reporting_expense_estimate_fact
             where tenant_id = ? and period_end >= ? and period_start <= ?
            """
                + filters
                + """
              group by fee_type, fee_sub_type, platform_code, site_code, channel_code, carrier_code,
                       shipping_method_code, shop_code, sku_code, currency, period_start, period_end
              order by fee_type, period_start, currency
            """,
            params.toArray());
    var out = new ArrayList<Group>();
    for (var row : rows) {
      out.add(
          new Group(
              row.get(0, String.class),
              row.get(1, String.class),
              row.get(2, String.class),
              row.get(3, String.class),
              row.get(4, String.class),
              row.get(5, String.class),
              row.get(6, String.class),
              row.get(7, String.class),
              row.get(8, String.class),
              row.get(9, String.class),
              row.get(10, LocalDate.class),
              row.get(11, LocalDate.class),
              new Amounts(
                  row.get(12, BigDecimal.class),
                  row.get(13, BigDecimal.class),
                  row.get(14, Long.class) == null ? 0L : row.get(14, Long.class),
                  "catalog")));
    }
    return out;
  }

  /** 实际侧的聚合结果（按日聚合，便于按预计侧的期间窗口取数）。 */
  private List<Group> actualGroups(
      UUID tenantId,
      LocalDate from,
      LocalDate to,
      String feeType,
      String platformCode,
      String shopCode,
      String skuCode) {
    var params = new ArrayList<Object>();
    params.add(tenantId);
    params.add(from);
    params.add(to);
    var filters = new StringBuilder();
    if (feeType != null && !feeType.isBlank()) {
      filters.append(" and fee_type = ?");
      params.add(feeType);
    }
    if (platformCode != null && !platformCode.isBlank()) {
      filters.append(" and platform_code = ?");
      params.add(platformCode);
    }
    if (shopCode != null && !shopCode.isBlank()) {
      filters.append(" and shop_code = ?");
      params.add(shopCode);
    }
    if (skuCode != null && !skuCode.isBlank()) {
      filters.append(" and sku_code = ?");
      params.add(skuCode);
    }
    var rows =
        sql.fetch(
            """
            select fee_type, fee_sub_type, platform_code, site_code, channel_code, carrier_code,
                   shipping_method_code, shop_code, sku_code, currency, period_start, period_end,
                   sum(actual_amount) as amount, coalesce(sum(base_amount), 0) as base, count(*) as cnt,
                   string_agg(distinct source_service, '+' order by source_service) as sources
              from reporting_expense_actual_fact
             where tenant_id = ? and period_end >= ? and period_start <= ?
            """
                + filters
                + """
              group by fee_type, fee_sub_type, platform_code, site_code, channel_code, carrier_code,
                       shipping_method_code, shop_code, sku_code, currency, period_start, period_end
              order by fee_type, period_start, currency
            """,
            params.toArray());
    var out = new ArrayList<Group>();
    for (var row : rows) {
      out.add(
          new Group(
              row.get(0, String.class),
              row.get(1, String.class),
              row.get(2, String.class),
              row.get(3, String.class),
              row.get(4, String.class),
              row.get(5, String.class),
              row.get(6, String.class),
              row.get(7, String.class),
              row.get(8, String.class),
              row.get(9, String.class),
              row.get(10, LocalDate.class),
              row.get(11, LocalDate.class),
              new Amounts(
                  row.get(12, BigDecimal.class),
                  row.get(13, BigDecimal.class),
                  row.get(14, Long.class) == null ? 0L : row.get(14, Long.class),
                  row.get(15, String.class))));
    }
    return out;
  }

  /** 把落在某个预计期间窗口内的实际值加起来。 */
  private Amounts sumActualsWithin(List<Group> actuals, Group estimate) {
    BigDecimal amount = null;
    BigDecimal base = null;
    long count = 0;
    var sources = new java.util.TreeSet<String>();
    for (var actual : actuals) {
      if (!actual.actualKey().equals(estimate.actualKey())) continue;
      if (actual.periodStart().isBefore(estimate.periodStart())) continue;
      if (actual.periodStart().isAfter(estimate.periodEnd())) continue;
      amount = amount == null ? actual.amounts().amount() : amount.add(actual.amounts().amount());
      base = base == null ? actual.amounts().base() : base.add(actual.amounts().base());
      count += actual.amounts().count();
      sources.add(actual.amounts().sources());
    }
    return new Amounts(amount, base, count, String.join("+", sources));
  }

  /** 这一组实际值是否落在某个预计期间的窗口内（用于「有实际无预计」的判定）。 */
  private boolean coveredByEstimate(List<Group> estimates, Group actual) {
    for (var estimate : estimates) {
      if (!estimate.actualKey().equals(actual.actualKey())) continue;
      if (actual.periodStart().isBefore(estimate.periodStart())) continue;
      if (actual.periodStart().isAfter(estimate.periodEnd())) continue;
      return true;
    }
    return false;
  }

  /** 「有实际、无预计」的原因（每类费用写清是哪一侧缺什么）。 */
  private String unmatchedReason(Group group) {
    return switch (group.feeType()) {
      case "PLATFORM_FEE" ->
          "这一组实际费用没有可对照的预计值：12.3.1.2 结算明细不带销售额 / 数量基数，比例规则与按件规则重算不出来。";
      case "LOGISTICS_FEE" ->
          "这一组实际费用没有可对照的预计值：12.3.1.2 结算明细不带计费量与件数，运费规则重算不出来。";
      case "AD_FEE" ->
          "这一组实际费用没有可对照的预计值：它落在广告预算方案的统计窗口之外（或该平台 / 店铺还没有预算方案）。";
      case "HEAD_FREIGHT" ->
          "这一组实际费用没有可对照的预计值：该 SKU 的成本构成里取不到 FIRST_MILE 分项（上游读不到或该分项未维护）。";
      default -> "这一组实际费用没有可对照的预计值。";
    };
  }

  // ==========================================================================
  // 趋势
  // ==========================================================================

  /**
   * 偏差趋势：把差异行按期间粒度分桶。
   *
   * <p>⚠️ **没有可比行的期间没有点**（不是 0%）：那一段是「算不出来」还是「真的是持平」，
   * 在页面上必须分开。
   */
  private List<ReportingExpenseModels.ExpenseVarianceTrendPoint> trend(
      List<ReportingExpenseModels.ExpenseVarianceRow> rows, String granularity) {
    var buckets = new LinkedHashMap<String, TrendBucket>();
    for (var row : rows) {
      if (!row.comparable()) continue;
      var bucket = bucket(row.periodStart(), granularity);
      var key = row.feeType() + "|" + row.feeSubType() + "|" + row.currency();
      var existing = buckets.get(key);
      if (existing == null) {
        existing = new TrendBucket(bucket, row.feeType(), row.feeSubType(), row.currency());
        buckets.put(key, existing);
      }
      existing.estimated = nvl(existing.estimated).add(nvl(row.estimatedAmount()));
      existing.actual = nvl(existing.actual).add(nvl(row.actualAmount()));
      existing.periodEnd = row.periodEnd();
    }
    var out = new ArrayList<ReportingExpenseModels.ExpenseVarianceTrendPoint>();
    for (var bucket : buckets.values()) {
      var varianceAmount = bucket.actual.subtract(bucket.estimated);
      var rate =
          bucket.estimated.signum() == 0
              ? null
              : varianceAmount
                  .multiply(new BigDecimal("100"))
                  .divide(bucket.estimated, 8, RoundingMode.HALF_UP)
                  .setScale(RATE_SCALE, RoundingMode.HALF_UP);
      out.add(
          new ReportingExpenseModels.ExpenseVarianceTrendPoint(
              bucket.feeType,
              label(bucket.feeType),
              bucket.feeSubType,
              bucket.currency,
              bucket.periodStart,
              bucket.periodEnd,
              bucket.estimated,
              bucket.actual,
              varianceAmount,
              rate,
              "全部归属维度"));
    }
    out.sort(
        Comparator.comparing(ReportingExpenseModels.ExpenseVarianceTrendPoint::feeType)
            .thenComparing(ReportingExpenseModels.ExpenseVarianceTrendPoint::periodStart)
            .thenComparing(ReportingExpenseModels.ExpenseVarianceTrendPoint::currency));
    return out;
  }

  /** 趋势的分桶。 */
  private static final class TrendBucket {
    final LocalDate periodStart;
    final String feeType;
    final String feeSubType;
    final String currency;
    LocalDate periodEnd;
    BigDecimal estimated = BigDecimal.ZERO;
    BigDecimal actual = BigDecimal.ZERO;

    TrendBucket(LocalDate periodStart, String feeType, String feeSubType, String currency) {
      this.periodStart = periodStart;
      this.feeType = feeType;
      this.feeSubType = feeSubType;
      this.currency = currency;
      this.periodEnd = periodStart;
    }
  }

  /** 期间分桶：日 / 周（周一为起点）/ 月。 */
  private static LocalDate bucket(LocalDate date, String granularity) {
    if (date == null) return null;
    if ("WEEK".equals(granularity)) {
      return date.with(DayOfWeek.MONDAY).minusDays(date.getDayOfWeek().getValue() - 1L);
    }
    if ("MONTH".equals(granularity)) {
      return date.withDayOfMonth(1);
    }
    return date;
  }

  // ==========================================================================
  // 规则调优建议
  // ==========================================================================

  /**
   * 规则调优建议：找出**系统性**的高估 / 低估。
   *
   * <p>⚠️ **样本不足不给建议**：一两笔的偏差可能是偶发，不是「系统性偏差」。
   *
   * <p>⚠️ **费率建议是反推出来的**：当前有效费率 = 预计 ÷ 基数，
   * 实际隐含费率 = 实际 ÷ 基数 ⇒ 建议值 = 实际隐含费率。
   * 没有基数（广告预算 / 结算明细）时**不给数字**，只给定性建议。
   */
  private List<ReportingExpenseModels.ExpenseVarianceRecommendation> recommendations(
      List<ReportingExpenseModels.ExpenseVarianceRow> rows, LocalDate from, LocalDate to) {
    var groups = new LinkedHashMap<String, RecommendationBucket>();
    for (var row : rows) {
      if (!row.comparable()) continue;
      var key = row.feeType() + "|" + row.feeSubType() + "|" + row.currency() + "|" + row.scopeLabel();
      var bucket = groups.get(key);
      if (bucket == null) {
        bucket = new RecommendationBucket(row);
        groups.put(key, bucket);
      }
      bucket.estimated = nvl(bucket.estimated).add(nvl(row.estimatedAmount()));
      bucket.actual = nvl(bucket.actual).add(nvl(row.actualAmount()));
      bucket.base = nvl(bucket.base).add(nvl(row.baseAmount()));
      bucket.samples++;
    }

    var out = new ArrayList<ReportingExpenseModels.ExpenseVarianceRecommendation>();
    for (var bucket : groups.values()) {
      if (bucket.samples < RECOMMENDATION_MIN_SAMPLES) continue;
      if (bucket.estimated.signum() == 0) continue;
      var varianceAmount = bucket.actual.subtract(bucket.estimated);
      var rate =
          varianceAmount
              .multiply(new BigDecimal("100"))
              .divide(bucket.estimated, 8, RoundingMode.HALF_UP)
              .setScale(RATE_SCALE, RoundingMode.HALF_UP);
      if (rate.abs().compareTo(RECOMMENDATION_THRESHOLD) < 0) continue;

      var overEstimated = rate.signum() < 0;
      var direction = overEstimated ? "OVER_ESTIMATED" : "UNDER_ESTIMATED";
      BigDecimal currentRate = null;
      BigDecimal impliedRate = null;
      if (bucket.base.signum() != 0) {
        currentRate = bucket.estimated.divide(bucket.base, 8, RoundingMode.HALF_UP).setScale(6, RoundingMode.HALF_UP);
        impliedRate = bucket.actual.divide(bucket.base, 8, RoundingMode.HALF_UP).setScale(6, RoundingMode.HALF_UP);
      }
      var feeType = ReportingExpenseModels.FeeType.parse(bucket.feeType);
      var ownerModule = feeType == null ? "" : feeType.ownerModule();
      var suggestion =
          suggestionText(
              bucket,
              feeType == null ? bucket.feeType : feeType.label(),
              ownerModule,
              direction,
              rate,
              currentRate,
              impliedRate,
              from,
              to);
      out.add(
          new ReportingExpenseModels.ExpenseVarianceRecommendation(
              bucket.feeType,
              feeType == null ? bucket.feeType : feeType.label(),
              bucket.feeSubType,
              bucket.currency,
              bucket.scopeLabel,
              ownerModule,
              direction,
              bucket.estimated,
              bucket.actual,
              varianceAmount,
              rate,
              currentRate,
              impliedRate,
              impliedRate,
              bucket.samples,
              suggestion,
              "样本 "
                  + bucket.samples
                  + " 组可比行；差异率 =（实际 − 预计）÷ 预计 × 100；阈值 "
                  + RECOMMENDATION_THRESHOLD.toPlainString()
                  + "%。"));
    }
    out.sort(
        Comparator.comparing(
                (ReportingExpenseModels.ExpenseVarianceRecommendation r) ->
                    r.varianceRate() == null ? BigDecimal.ZERO : r.varianceRate().abs())
            .reversed());
    return out;
  }

  /** 建议聚合桶。 */
  private static final class RecommendationBucket {
    final String feeType;
    final String feeSubType;
    final String currency;
    final String scopeLabel;
    BigDecimal estimated = BigDecimal.ZERO;
    BigDecimal actual = BigDecimal.ZERO;
    BigDecimal base = BigDecimal.ZERO;
    long samples;

    RecommendationBucket(ReportingExpenseModels.ExpenseVarianceRow row) {
      this.feeType = row.feeType();
      this.feeSubType = row.feeSubType();
      this.currency = row.currency();
      this.scopeLabel = row.scopeLabel();
    }
  }

  /** 建议正文（人话，把「改哪里的哪个数」说清楚）。 */
  private String suggestionText(
      RecommendationBucket bucket,
      String feeTypeLabel,
      String ownerModule,
      String direction,
      BigDecimal rate,
      BigDecimal currentRate,
      BigDecimal impliedRate,
      LocalDate from,
      LocalDate to) {
    var over = "OVER_ESTIMATED".equals(direction);
    var head =
        over
            ? feeTypeLabel + "在 " + bucket.scopeLabel + " 上持续**高估**"
            : feeTypeLabel + "在 " + bucket.scopeLabel + " 上持续**低估**";
    var body =
        "（差异率 "
            + rate.toPlainString()
            + "%，预计 "
            + bucket.estimated.toPlainString()
            + " "
            + bucket.currency
            + "，实际 "
            + bucket.actual.toPlainString()
            + " "
            + bucket.currency
            + "，"
            + bucket.samples
            + " 组可比样本，"
            + from
            + " ~ "
            + to
            + "）";
    if (impliedRate != null && currentRate != null) {
      return head
          + body
          + "。建议把 "
          + ownerModule
          + " 的费率从 "
          + currentRate.toPlainString()
          + " 调到 "
          + impliedRate.toPlainString()
          + "（按实际发生额反推：实际 ÷ 基数）。";
    }
    return head
        + body
        + "。这一类费用没有销售额 / 数量基数，**反推不出费率**，建议按预计 "
        + bucket.estimated.toPlainString()
        + " 与实际 "
        + bucket.actual.toPlainString()
        + " 的比例校准 "
        + ownerModule
        + " 的规则或预算。";
  }

  // ==========================================================================
  // 工具
  // ==========================================================================

  /** 归属维度的中文说明。 */
  private static String scopeLabel(Group group) {
    var parts = new ArrayList<String>();
    if (!group.platformCode().isBlank()) parts.add("平台 " + group.platformCode());
    if (!group.siteCode().isBlank()) parts.add("站点 " + group.siteCode());
    if (!group.channelCode().isBlank()) parts.add("渠道 " + group.channelCode());
    if (!group.carrierCode().isBlank()) parts.add("承运商 " + group.carrierCode());
    if (!group.shippingMethodCode().isBlank()) parts.add("运输方式 " + group.shippingMethodCode());
    if (!group.shopCode().isBlank()) parts.add("店铺 " + group.shopCode());
    if (!group.skuCode().isBlank()) parts.add("SKU " + group.skuCode());
    return parts.isEmpty() ? "全部" : String.join(" / ", parts);
  }

  /** 费用类型的中文名（不认识就原样返回，不编一个名字）。 */
  private static String label(String feeType) {
    var parsed = ReportingExpenseModels.FeeType.parse(feeType);
    return parsed == null ? feeType : parsed.label();
  }

  /**
   * 数据更新时间（两张事实表取较晚者）。
   *
   * <p>⚠️ **两张表都空 ⇒ null**，不是 {@code -infinity}：用 {@code -infinity} 当哨兵时，
   * 空表会算出一个「比公元无限还早」的时间戳，页面上会显示成「数据截至 -infinity」——
   * 那是一个长得像时间的假值。宁可给 null（页面上写「尚未投影任何数据」）。
   *
   * <p>⚠️ **逐字照抄 13.1.1.1 {@code ReportingOverviewService.dataUpdatedAt} 的写法**：
   * 两条简单查询 + Java 侧比较，不在 SQL 里拼 {@code greatest(...)}。
   */
  private OffsetDateTime dataUpdatedAt(UUID tenantId) {
    var estimateRow =
        sql.fetchOne(
            "select max(updated_at) from reporting_expense_estimate_fact where tenant_id = ?",
            tenantId);
    var actualRow =
        sql.fetchOne(
            "select max(updated_at) from reporting_expense_actual_fact where tenant_id = ?",
            tenantId);
    var estimateAt = estimateRow == null ? null : estimateRow.get(0, OffsetDateTime.class);
    var actualAt = actualRow == null ? null : actualRow.get(0, OffsetDateTime.class);
    if (estimateAt == null) return actualAt;
    if (actualAt == null) return estimateAt;
    return estimateAt.isAfter(actualAt) ? estimateAt : actualAt;
  }

  /** 口径说明（预计侧与实际侧分别来自哪个上游）。 */
  private static String caliberNote() {
    return "预计费用：平台费 / 物流费由 catalog 5.2.3 的规则逐行测算（阶梯规则按该行的金额选档）；"
        + "头程分摊取 catalog 5.2.1.1 成本构成的 FIRST_MILE 分项；"
        + "广告费取 14 广告预算分配方案的逐活动分配额（期间 = 方案的统计窗口）。"
        + "实际费用：平台费 / 物流费取 catalog 5.2.3 的实际台账（人工登记的平台 / 物流账单观察值）；"
        + "头程分摊取 catalog 5.2.1.3 成本分摊的计算分摊额；"
        + "12.3.1.2 结算明细的佣金 / FBA 费用 / 广告费也进实际侧。"
        + "跨币种绝不相加。";
  }

  private static BigDecimal nvl(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }

  /** 追加 limit / offset 参数。 */
  private static Object[] append(List<Object> params, int size, int offset) {
    var out = new ArrayList<>(params);
    out.add(size);
    out.add(offset);
    return out.toArray();
  }

  /**
   * SKU 级差异查询的参数顺序（**必须与 SQL 里的 {@code ?} 出现顺序逐字对应**）：
   * 预计侧（租户 / 起 / 止 / 费用类型）→ 实际侧（租户 / 起 / 止）。
   */
  private static List<Object> rowParams(
      UUID tenantId, LocalDate from, LocalDate to, String feeType) {
    var params = new ArrayList<Object>();
    params.add(tenantId);
    params.add(from);
    params.add(to);
    if (feeType != null && !feeType.isBlank()) params.add(feeType);
    params.add(tenantId);
    params.add(from);
    params.add(to);
    return params;
  }
}
