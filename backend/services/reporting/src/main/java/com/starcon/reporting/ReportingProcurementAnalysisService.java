package com.starcon.reporting;

import com.starcon.tenancy.Ids;
import com.starcon.tenancy.TenantTransactions;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Service;

/**
 * 13.5.2.1 采购分析的**读模型**（蓝图「输出」句：采购报表 + 趋势 + 供应商对比）。
 *
 * <p>⚠️ <b>七件指标，一个口径</b>：
 *
 * <ol>
 *   <li><b>采购金额趋势与占比</b> —— 逐币种，按期间（DAY / WEEK / MONTH）出点，
 *       占比的分母是**同币种**的窗口合计；</li>
 *   <li><b>供应商集中度</b> —— 逐币种出 top-N 占比 + HHI + **风险信号**
 *       （蓝图加粗句：单一供应商占比过高会在断供时直接击穿业务）；</li>
 *   <li><b>价格波动</b> —— 当前期间 vs **上一等长期间**的加权均价变化；</li>
 *   <li><b>到货准时率</b> —— **判定归 trade 6.3.2.2**，本模块只搬运；</li>
 *   <li><b>采购周期</b> —— 到货日 − 下单日（catalog 6.1.1.1 的到货记录）；</li>
 *   <li><b>采购成本占销售额比</b> —— 逐币种：采购额 ÷ **同币种**销售额
 *       （销售额取 13.1.1.1 已投影的 {@code reporting_metric_fact}，**不重算**）；</li>
 *   <li><b>供应商对比</b> —— 采购贡献 + 准时率 + 采购周期 + **供应商评级**。</li>
 * </ol>
 *
 * <p>🔴 <b>三条硬纪律贯穿本类</b>：
 *
 * <ol>
 *   <li><b>分母为 0 ⇒ null（不可比），不是 0</b>。「准时率 0%」与「这一格算不出来」
 *       在报表里长得一模一样，含义却完全相反。</li>
 *   <li><b>多币种绝不相加</b>。每一行带自己的币种；跨币种的合计不存在。</li>
 *   <li><b>先汇总再相除</b>。占比与比率的分母都是**先加总**再相除，
 *       不是对逐行的比率求平均。</li>
 * </ol>
 */
@Service
public class ReportingProcurementAnalysisService {

  /** 金额刻度（对齐 {@code numeric(24,4)}）。 */
  static final int MONEY_SCALE = 4;

  /** 数量刻度（对齐 {@code numeric(19,6)}）。 */
  static final int QUANTITY_SCALE = 6;

  /** 比率刻度（百分数，保留 4 位）。 */
  static final int RATE_SCALE = 4;

  /** 默认集中度风险线：前 N 家供应商合计占比超过这个百分数 ⇒ 高风险。 */
  static final int DEFAULT_TOP_N = 3;

  /** 默认集中度风险线：**前 N 家合计**占比（百分数）。 */
  static final BigDecimal DEFAULT_TOP_SHARE_PCT = new BigDecimal("60");

  /** 默认集中度风险线：**单一供应商**占比（百分数）⇒ 达到即高风险。 */
  static final BigDecimal DEFAULT_SINGLE_SUPPLIER_SHARE_PCT = new BigDecimal("40");

  /** 默认准时率目标（百分数）。 */
  static final BigDecimal DEFAULT_ON_TIME_TARGET = new BigDecimal("90");

  /** 默认采购周期目标（天）。 */
  static final BigDecimal DEFAULT_CYCLE_TARGET_DAYS = new BigDecimal("30");

  /** HHI 的刻度：份额小数平方和 × 10000（0~10000）。 */
  static final int HHI_SCALE = 10000;

  /** 占比行最多列多少条（超出部分在 note 里说明，**不静默截断**）。 */
  static final int SHARE_ROW_LIMIT = 50;

  /** 逾期待到货清单最多列多少条。 */
  static final int LATE_ROW_LIMIT = 50;

  private static final String PURCHASE_CALIBER =
      "采购金额口径 = **trade 6.5.2.1 的到货流水**（fact_kind=COST / source_type=ARRIVAL_RECEIPT）"
          + "——**分批到货按流水累加**，不压成「一张采购单一行」。"
          + "catalog 6.1.1.1 的到货记录**只用于采购周期**，它的金额**不参与任何合计**"
          + "（否则同一笔采购会被算两次）。";

  private static final String CYCLE_CALIBER =
      "采购周期 = **到货日 − 下单日**（catalog 6.1.1.1 的到货记录；"
          + "下单时刻按该行记录的时区换算成下单日，与到货日同口径才能做差）。"
          + "这是**实际采购周期**，不是「承诺到货日 − 下单日」那个**承诺交期**。";

  private static final String ON_TIME_CALIBER =
      "到货准时率 = 准时流水数 ÷ **已到货流水数**（PENDING 未到货**不进分母**）；"
          + "**判定归 trade 6.3.2.2**（承诺到货日 vs 实际到货日），本模块只搬运、不重算。";

  private static final String CURRENCY_NOTE =
      "**多币种绝不相加**：每一行带自己的币种，跨币种的「总计」不存在（单币种时才给）。"
          + "本服务**不自行折算币种**（没有汇率台账）。";

  private final DSLContext sql;
  private final TenantTransactions tenants;

  public ReportingProcurementAnalysisService(DSLContext sql, TenantTransactions tenants) {
    this.sql = sql;
    this.tenants = tenants;
  }

  // ==========================================================================
  // 能力声明
  // ==========================================================================

  /**
   * 能力声明：七件指标逐条报「接得通 / 接不通」。
   *
   * <p>🔴 **接不通的必须给出归属模块号与原因** —— 「算不出来」与「数值是 0」
   * 在页面上长得一样，含义却相反。
   */
  public ReportingProcurementAnalysisModels.ProcurementAnalysisCapabilities capabilities(
      UUID tenantId) {
    return tenants.run(
        tenantId,
        () -> {
          var purchaseFacts = countOf(sql.fetchOne(
              "select count(*) from reporting_procurement_analysis_purchase_fact where tenant_id = ?",
              tenantId));
          var deliveries = countOf(sql.fetchOne(
              "select count(*) from reporting_procurement_analysis_delivery_fact where tenant_id = ?",
              tenantId));
          var suppliers = countOf(sql.fetchOne(
              "select count(*) from reporting_procurement_analysis_supplier_fact where tenant_id = ?",
              tenantId));
          var ratedSuppliers = countOf(sql.fetchOne(
              "select count(*) from reporting_procurement_analysis_supplier_fact"
                  + " where tenant_id = ? and rating_score is not null",
              tenantId));
          var salesFactCount = countOf(sql.fetchOne(
              "select count(*) from reporting_metric_fact where tenant_id = ?"
                  + " and scope_type = 'COMPANY' and metric_code = 'SALES_AMOUNT'",
              tenantId));

          var sections = new ArrayList<ReportingProcurementAnalysisModels.ProcurementAnalysisCapabilitySection>();

          sections.add(section(
              "PURCHASE_AMOUNT_TREND",
              "采购金额趋势与占比",
              purchaseFacts > 0,
              null,
              null,
              null,
              PURCHASE_CALIBER,
              "trade 6.5.2.1 `GET /trade/purchase-analytics/facts`（fact_kind=COST）"
                  + "→ `reporting_procurement_analysis_purchase_fact`"));
          sections.add(section(
              "SUPPLIER_CONCENTRATION",
              "供应商集中度",
              purchaseFacts > 0,
              null,
              null,
              null,
              "逐币种的 top-N 占比 + HHI（0~10000）+ **风险等级**；"
                  + "风险线由租户自己的规则表给（默认前 3 家合计 ≥ 40% ⇒ 高风险）。",
              "同一批采购流水按供应商聚合 → `reporting_procurement_analysis_purchase_fact`"));
          sections.add(section(
              "PRICE_VARIANCE",
              "采购价格波动",
              purchaseFacts > 0,
              null,
              null,
              null,
              "当前期间 vs **上一等长期间**的加权均价（金额 ÷ 数量）；"
                  + "任一侧没有数量 ⇒ 均价**不可比**（不是 0）。",
              "同一批采购流水按 SKU / 供应商聚合"));
          sections.add(section(
              "ON_TIME_RATE",
              "到货准时率",
              purchaseFacts > 0,
              null,
              null,
              null,
              ON_TIME_CALIBER,
              "trade 6.3.2.2 的准时判定（`on_time_status`），随采购流水一起搬运"));
          sections.add(section(
              "PURCHASE_CYCLE",
              "采购周期",
              deliveries > 0,
              deliveries > 0 ? null : "6.1.1.1 供应商到货记录（catalog，已交付但本租户没有到货记录）",
              deliveries > 0 ? null
                  : "catalog 6.1.1.1 的到货记录是**采购周期的唯一来源**"
                      + "（全仓只有它同时有下单日与到货日）；"
                      + "本租户还没有到货记录 ⇒ 采购周期**不可比**（不是 0 天）。",
              deliveries > 0 ? null
                  : "供应商对比里的「平均采购周期」一列是「—（暂无到货记录）」，"
                      + "补货与备选供应商判断会少一个依据。",
              CYCLE_CALIBER,
              "catalog 6.1.1.1 `GET /catalog/supply/vendors/{roleId}` 的 deliveries[]"
                  + " → `reporting_procurement_analysis_delivery_fact`"));
          sections.add(section(
              "COST_TO_SALES",
              "采购成本占销售额比",
              salesFactCount > 0,
              salesFactCount > 0 ? null : "13.1.1.1 经营总览的投影（reporting 服务，已交付）",
              salesFactCount > 0 ? null
                  : "**分母（销售额）取 13.1.1.1 已投影的 `reporting_metric_fact`（SALES_AMOUNT）**"
                      + "——本模块**不重算**销售额（口径唯一）。该投影还没跑 ⇒ 分母为 0 ⇒ "
                      + "成本占比**不可比**（不是 0%）。",
              salesFactCount > 0 ? null
                  : "下游 13.4 利润会少一个先行指标（采购成本占比是利润健康度的先行指标）。",
              "逐币种：采购额 ÷ **同币种**销售额；本服务**不自行折算币种**。",
              "reporting 本服务的 `reporting_metric_fact`（scope_type=COMPANY, metric_code=SALES_AMOUNT）"));
          sections.add(section(
              "SUPPLIER_COMPARISON",
              "供应商对比",
              suppliers > 0,
              null,
              null,
              null,
              "采购贡献 + 准时率 + 采购周期 + **供应商评级**（6.1.1.3 的最新一期）。",
              "catalog 6.1.1.1 供应商列表 + trade 6.1.1.3 供应商评级"
                  + " → `reporting_procurement_analysis_supplier_fact`"));
          sections.add(section(
              "SUPPLIER_RATING",
              "供应商评级",
              ratedSuppliers > 0,
              ratedSuppliers > 0 ? null : "6.1.1.3 供应商评级（trade，已交付但本租户还没有评级结果）",
              ratedSuppliers > 0 ? null
                  : "6.1.1.3 已交付，但本租户还没有评级结果 ⇒ 评级列是「—（未评级）」，"
                      + "**不是 0 分**（把「没评过」写成 0 分等于替上游断言「这家很差」）。",
              ratedSuppliers > 0 ? null
                  : "供应商对比里少了评级这一维，谈判优先级只能看金额与准时率。",
              "评级 = 最终分（客观分 + 人工调整量，上游 DB 层 CHECK 钉死）；历史仍归 6.1.1.3。",
              "trade 6.1.1.3 `GET /trade/supplier-ratings/ratings`"));

          return new ReportingProcurementAnalysisModels.ProcurementAnalysisCapabilities(
              sections.stream().filter(ReportingProcurementAnalysisModels.ProcurementAnalysisCapabilitySection::available)
                  .map(ReportingProcurementAnalysisModels.ProcurementAnalysisCapabilitySection::key)
                  .toList(),
              sections,
              "上游共两路："
                  + "**trade**（6.5.2.1 采购事实台账 + 6.1.1.3 供应商评级）与 "
                  + "**catalog**（6.1.1.1 供应商档案 + 到货记录）。"
                  + "两路都经**授权只读 HTTP**读，转发调用方自己的 bearer，**不直连对方库**。",
              PURCHASE_CALIBER,
              CURRENCY_NOTE,
              "供应商集中度**不只是中性百分比**：本模块给出风险等级（LOW / MEDIUM / HIGH）"
                  + "与风险说明（哪一家、占比多少、断供时会怎样）。"
                  + "蓝图加粗句：「供应商集中度是供应链风险指标，单一供应商占比过高会在断供时直接击穿业务。」",
              CYCLE_CALIBER,
              "**销售额分母来自 13.1.1.1 已投影的 `reporting_metric_fact`**（口径唯一，本模块不重算）；"
                  + "该投影还没跑时，成本占比是「—（不可比）」，不是 0%。");
        });
  }

  private static ReportingProcurementAnalysisModels.ProcurementAnalysisCapabilitySection section(
      String key,
      String label,
      boolean available,
      String ownerModule,
      String reason,
      String impact,
      String caliber,
      String evidence) {
    return new ReportingProcurementAnalysisModels.ProcurementAnalysisCapabilitySection(
        key, label, available, ownerModule, reason, impact, caliber, evidence);
  }

  // ==========================================================================
  // ① 采购金额趋势与占比
  // ==========================================================================

  /**
   * 采购金额趋势与占比。
   *
   * @param dimension 拆解维度（ALL / SUPPLIER / CATEGORY / SHOP / SKU）
   * @param currency 币种过滤（空 ⇒ 全部；**绝不跨币种相加**）
   */
  public ReportingProcurementAnalysisModels.ProcurementTrendResponse trend(
      UUID tenantId, LocalDate from, LocalDate to, String granularity, String dimension, String currency) {
    return tenants.run(
        tenantId,
        () -> {
          var warnings = new ArrayList<String>();
          var periodExpr = periodExpression(granularity);
          var currencyFilter = currency == null ? "" : " and currency = ?";

          var points =
              sql.fetch(
                  "select " + periodExpr + " as period, currency, sum(amount) as amount,"
                      + " count(*) as fact_count, sum(quantity) as quantity"
                      + " from reporting_procurement_analysis_purchase_fact"
                      + " where tenant_id = ? and occurred_on between ? and ? and amount is not null"
                      + currencyFilter
                      + " group by 1,2 order by 1,2",
                  currency == null
                      ? new Object[] {tenantId, from, to}
                      : new Object[] {tenantId, from, to, currency});

          var totals =
              sql.fetch(
                  "select currency, sum(amount) as amount, count(*) as fact_count"
                      + " from reporting_procurement_analysis_purchase_fact"
                      + " where tenant_id = ? and occurred_on between ? and ? and amount is not null"
                      + currencyFilter
                      + " group by 1 order by 1",
                  currency == null
                      ? new Object[] {tenantId, from, to}
                      : new Object[] {tenantId, from, to, currency});

          // ⚠️ **金额不可得的行单独计数**：它们的金额是「算不出来」，不是 0，
          //   必须在页面上说清楚，否则「合计」会被读成全量。
          var missing =
              sql.fetchOne(
                  "select count(*) as rows, count(distinct supplier_id) as suppliers"
                      + " from reporting_procurement_analysis_purchase_fact"
                      + " where tenant_id = ? and occurred_on between ? and ? and amount is null",
                  tenantId,
                  from,
                  to);
          var missingRows = missing == null ? 0L : count(missing.get(0, Long.class));
          var missingSuppliers = missing == null ? 0L : count(missing.get(1, Long.class));

          var trendPoints = new ArrayList<ReportingProcurementAnalysisModels.ProcurementTrendPoint>();
          for (var row : points) {
            trendPoints.add(
                new ReportingProcurementAnalysisModels.ProcurementTrendPoint(
                    row.get(0, String.class),
                    row.get(1, String.class),
                    row.get(2, BigDecimal.class),
                    count(row.get(3, Long.class)),
                    row.get(4, BigDecimal.class)));
          }

          var currencyTotals = new ArrayList<ReportingProcurementAnalysisModels.ProcurementTrendTotal>();
          var currencies = new ArrayList<String>();
          var grandTotal = BigDecimal.ZERO;
          for (var row : totals) {
            var ccy = row.get(0, String.class);
            var amount = row.get(1, BigDecimal.class);
            currencies.add(ccy);
            if (amount != null) grandTotal = grandTotal.add(amount);
            currencyTotals.add(
                new ReportingProcurementAnalysisModels.ProcurementTrendTotal(
                    ccy, amount, count(row.get(2, Long.class))));
          }

          if (currencies.size() > 1) {
            warnings.add(
                "本次结果含 "
                    + currencies.size()
                    + " 个币种，**跨币种的合计不存在** —— 页面上的「总计」只在单币种时显示。");
          }
          if (missingRows > 0) {
            warnings.add(
                "有 "
                    + missingRows
                    + " 条到货流水（涉及 "
                    + missingSuppliers
                    + " 家供应商）**没有金额 / 币种**，它们的金额是「—」，**不在任何合计里**（不是 0 元）。");
          }

          // ---- 占比（按维度拆解；分母 = **同币种**的窗口合计）----
          var shares = new ArrayList<ReportingProcurementAnalysisModels.ProcurementTrendShareRow>();
          var perCurrencyTotal = new LinkedHashMap<String, BigDecimal>();
          for (var total : currencyTotals) {
            if (total.amount() != null) perCurrencyTotal.put(total.currency(), total.amount());
          }
          if (!dimension.equals("ALL")) {
            var spec = dimensionSpec(dimension);
            var shareRows =
                sql.fetch(
                    "select " + spec.key() + " as dim_key, " + spec.label() + " as dim_label, currency,"
                        + " sum(amount) as amount, count(*) as fact_count"
                        + " from reporting_procurement_analysis_purchase_fact"
                        + " where tenant_id = ? and occurred_on between ? and ? and amount is not null"
                        + currencyFilter
                        + " group by 1,2,3 order by 4 desc",
                    currency == null
                        ? new Object[] {tenantId, from, to}
                        : new Object[] {tenantId, from, to, currency});
            var perCurrency = new LinkedHashMap<String, Integer>();
            for (var row : shareRows) {
              var ccy = row.get(2, String.class);
              var seen = perCurrency.getOrDefault(ccy, 0);
              if (seen >= SHARE_ROW_LIMIT) {
                perCurrency.put(ccy, seen + 1);
                continue;
              }
              perCurrency.put(ccy, seen + 1);
              var amount = row.get(3, BigDecimal.class);
              var denominator = perCurrencyTotal.get(ccy);
              BigDecimal share = null;
              if (amount != null && denominator != null && denominator.signum() != 0) {
                share = amount.multiply(new BigDecimal("100")).divide(denominator, RATE_SCALE, RoundingMode.HALF_UP);
              }
              shares.add(
                  new ReportingProcurementAnalysisModels.ProcurementTrendShareRow(
                      dimension,
                      row.get(0, String.class),
                      spec.displayLabel(row.get(0, String.class), row.get(1, String.class)),
                      ccy,
                      amount,
                      share,
                      count(row.get(4, Long.class))));
            }
            var truncatedCurrencies =
                perCurrency.entrySet().stream()
                    .filter(e -> e.getValue() > SHARE_ROW_LIMIT)
                    .map(Map.Entry::getKey)
                    .toList();
            if (!truncatedCurrencies.isEmpty()) {
              warnings.add(
                  "占比明细每个币种只列前 "
                      + SHARE_ROW_LIMIT
                      + " 行（币种 "
                      + String.join("、", truncatedCurrencies)
                      + " 有更多行未列出）—— **不是**分页，合计仍然覆盖全部行。");
            }
          }

          return new ReportingProcurementAnalysisModels.ProcurementTrendResponse(
              from,
              to,
              granularity,
              dimension,
              currencies,
              trendPoints,
              currencyTotals,
              currencies.size() == 1 ? grandTotal : null,
              currencies.size() == 1 && !currencies.isEmpty() ? currencies.get(0) : null,
              shares,
              dataUpdatedAt(tenantId),
              DATA_UPDATED_NOTE,
              PURCHASE_CALIBER,
              warnings);
        });
  }

  // ==========================================================================
  // ② 供应商集中度（**风险信号**）
  // ==========================================================================

  /**
   * 供应商集中度 + 风险信号。
   *
   * <p>🔴 **蓝图加粗句的落点**：集中度不是「一个中性的百分比」，而是
   * 「断供时业务会不会被打穿」的判据 ⇒ 本方法给出**风险等级**与**风险说明**
   * （哪一家、占比多少、断供时这一档采购额会中断）。
   */
  public ReportingProcurementAnalysisModels.ProcurementConcentrationResponse concentration(
      UUID tenantId, LocalDate from, LocalDate to, String currency) {
    return tenants.run(
        tenantId,
        () -> {
          var warnings = new ArrayList<String>();
          var rule = ruleOf(tenantId, "CONCENTRATION_RISK");
          var topN = rule == null || rule.concentrationTopN() == null ? DEFAULT_TOP_N : rule.concentrationTopN();
          var threshold =
              rule == null || rule.concentrationTopSharePct() == null
                  ? DEFAULT_TOP_SHARE_PCT
                  : rule.concentrationTopSharePct();
          var singleThreshold =
              rule == null || rule.singleSupplierSharePct() == null
                  ? DEFAULT_SINGLE_SUPPLIER_SHARE_PCT
                  : rule.singleSupplierSharePct();

          var rows =
              sql.fetch(
                  "select supplier_id, supplier_code, supplier_name, currency,"
                      + " sum(amount) as amount, count(*) as fact_count, sum(quantity) as quantity"
                      + " from reporting_procurement_analysis_purchase_fact"
                      + " where tenant_id = ? and occurred_on between ? and ? and amount is not null"
                      + (currency == null ? "" : " and currency = ?")
                      + " group by 1,2,3,4 order by 4,5 desc",
                  currency == null
                      ? new Object[] {tenantId, from, to}
                      : new Object[] {tenantId, from, to, currency});

          // 供应商评级 + 准时率（**同一批采购流水**上算，不另找口径）
          var ratingBySupplier = ratingBySupplier(tenantId);
          var onTimeBySupplier = onTimeBySupplier(tenantId, from, to);

          var byCurrency = new LinkedHashMap<String, List<Object[]>>();
          for (var row : rows) {
            byCurrency
                .computeIfAbsent(row.get(3, String.class), k -> new ArrayList<>())
                .add(new Object[] {row});
          }

          var buckets = new ArrayList<ReportingProcurementAnalysisModels.ProcurementConcentrationBucket>();
          String overallRisk = "LOW";
          String overallNote = "";
          String topSupplierName = null;
          BigDecimal topSupplierShare = null;

          for (var entry : byCurrency.entrySet()) {
            var ccy = entry.getKey();
            var total = BigDecimal.ZERO;
            long factCount = 0;
            for (var raw : entry.getValue()) {
              var row = (org.jooq.Record) raw[0];
              var amount = row.get(4, BigDecimal.class);
              if (amount != null) total = total.add(amount);
              factCount += count(row.get(5, Long.class));
            }

            var suppliers = new ArrayList<ReportingProcurementAnalysisModels.ProcurementConcentrationSupplier>();
            var cumulative = BigDecimal.ZERO;
            var top1Share = BigDecimal.ZERO;
            var topNShare = BigDecimal.ZERO;
            var hhi = BigDecimal.ZERO;
            var index = 0;
            for (var raw : entry.getValue()) {
              var row = (org.jooq.Record) raw[0];
              var supplierId = row.get(0, UUID.class);
              var amount = row.get(4, BigDecimal.class);
              BigDecimal share = null;
              if (amount != null && total.signum() != 0) {
                share = amount.multiply(new BigDecimal("100")).divide(total, RATE_SCALE, RoundingMode.HALF_UP);
                cumulative = cumulative.add(share);
                var shareFraction =
                    amount.divide(total, 10, RoundingMode.HALF_UP);
                hhi = hhi.add(shareFraction.multiply(shareFraction).multiply(new BigDecimal(HHI_SCALE)));
              }
              if (index == 0 && share != null) top1Share = share;
              if (index < topN && share != null) topNShare = topNShare.add(share);
              index++;

              var rating = ratingBySupplier.get(supplierId);
              var onTime = onTimeBySupplier.get(supplierId);
              suppliers.add(
                  new ReportingProcurementAnalysisModels.ProcurementConcentrationSupplier(
                      supplierId,
                      row.get(1, String.class),
                      row.get(2, String.class),
                      amount,
                      share,
                      cumulative,
                      count(row.get(5, Long.class)),
                      row.get(6, BigDecimal.class),
                      onTime == null ? null : onTime.rate(),
                      rating == null ? null : rating.score(),
                      rating == null ? null : rating.grade(),
                      null));
            }

            var currencyRisk = riskLevel(top1Share, topNShare, singleThreshold, threshold);
            var currencyNote =
                riskNote(
                    suppliers.isEmpty() ? null : suppliers.get(0).supplierName(),
                    top1Share,
                    topNShare,
                    hhi,
                    singleThreshold,
                    threshold,
                    topN,
                    currencyRisk);
            buckets.add(
                new ReportingProcurementAnalysisModels.ProcurementConcentrationBucket(
                    ccy,
                    total,
                    suppliers.size(),
                    factCount,
                    suppliers,
                    top1Share,
                    topNShare,
                    hhi.setScale(RATE_SCALE, RoundingMode.HALF_UP),
                    "0~10000（份额小数平方和 × 10000；单一供应商 100% ⇒ 10000）",
                    currencyRisk,
                    currencyNote));

            if (rank(currencyRisk) > rank(overallRisk)) {
              overallRisk = currencyRisk;
              overallNote = "[" + ccy + "] " + currencyNote;
              if (!suppliers.isEmpty()) {
                topSupplierName = suppliers.get(0).supplierName();
                topSupplierShare = top1Share;
              }
            }
          }

          if (buckets.isEmpty()) {
            // ⚠️ **一个币种段都没有 ⇒ 连「算不出来」这句话都要说清楚**：
            //   风险等级留 LOW 会让人以为「没有风险」，而真相是「这一格算不出来」。
            overallNote =
                "这个窗口里**没有带金额的到货流水** —— 采购集中度**算不出来**（不是 0%，"
                    + "也不是「没有风险」）。先刷新投影（POST /reporting/procurement-analysis/refresh）。";
            warnings.add(
                "这个窗口里**没有带金额的到货流水** —— 采购集中度算不出来（不是 0%）。"
                    + "先刷新投影（POST /reporting/procurement-analysis/refresh）。");
          }
          if (buckets.size() > 1) {
            warnings.add("多个币种**各自成一段**：集中度不跨币种合并（跨币种相加会得到一个没有意义的数）。");
          }

          return new ReportingProcurementAnalysisModels.ProcurementConcentrationResponse(
              from,
              to,
              buckets,
              overallRisk,
              overallNote,
              topSupplierName,
              topSupplierShare,
              topN,
              threshold,
              singleThreshold,
              dataUpdatedAt(tenantId),
              DATA_UPDATED_NOTE,
              PURCHASE_CALIBER,
              warnings);
        });
  }

  /**
   * 风险等级。
   *
   * <p>🔴 **两种风险分开判**（蓝图加粗句只说了第一种）：
   *
   * <ul>
   *   <li><b>HIGH（高风险）</b> —— **单一供应商**占比 ≥ 单家阈值（默认 40%）。
   *       这一档对应「断供时直接击穿业务」。</li>
   *   <li><b>MEDIUM（中风险）</b> —— 没有单点依赖，但**前 N 家**（默认 3）合计占比
   *       ≥ 合计阈值（默认 60%）。结构偏集中，建议培养第二梯队。</li>
   *   <li><b>LOW（低风险）</b> —— 其余。</li>
   * </ul>
   *
   * <p>⚠️ **HHI 不参与定级，只作参考指数报出**：HHI 的固定档位在不同采购结构下
   * 含义完全不同（4 家 30/30/20/20 的 HHI 是 2600，但最大供应商只有 30%，
   * 说它是「高风险」比说「中等风险」更离谱）⇒ 定级只由**租户自己可配的两个阈值**决定。
   */
  private static String riskLevel(
      BigDecimal top1Share,
      BigDecimal topNShare,
      BigDecimal singleThreshold,
      BigDecimal topNThreshold) {
    if (top1Share.compareTo(singleThreshold) >= 0) return "HIGH";
    if (topNShare.compareTo(topNThreshold) >= 0) return "MEDIUM";
    return "LOW";
  }

  private static int rank(String risk) {
    return switch (risk) {
      case "HIGH" -> 3;
      case "MEDIUM" -> 2;
      default -> 1;
    };
  }

  /** 风险说明（写明是哪一家、占比多少、断供时会怎样）。 */
  private static String riskNote(
      String topSupplierName,
      BigDecimal top1Share,
      BigDecimal topNShare,
      BigDecimal hhi,
      BigDecimal singleThreshold,
      BigDecimal topNThreshold,
      int topN,
      String riskLevel) {
    if (top1Share == null || top1Share.signum() == 0) {
      return "这个窗口里没有可算集中度的采购金额（不是「没有风险」，是**算不出来**）。";
    }
    var name = topSupplierName == null || topSupplierName.isBlank() ? "最大供应商" : topSupplierName;
    var base =
        "最大供应商「"
            + name
            + "」占采购金额 "
            + top1Share.stripTrailingZeros().toPlainString()
            + "%，前 "
            + topN
            + " 家合计 "
            + topNShare.stripTrailingZeros().toPlainString()
            + "%，HHI "
            + hhi.setScale(RATE_SCALE, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
            + "（风险线：单一供应商 ≥ "
            + singleThreshold.stripTrailingZeros().toPlainString()
            + "% ⇒ 高风险；前 "
            + topN
            + " 家合计 ≥ "
            + topNThreshold.stripTrailingZeros().toPlainString()
            + "% ⇒ 中风险）。";
    return switch (riskLevel) {
      case "HIGH" ->
          base
              + " 🔴 **高风险**：这家断供时 "
              + top1Share.stripTrailingZeros().toPlainString()
              + "% 的采购额会直接中断，业务会被击穿 —— 需要备选供应商与安全库存。";
      case "MEDIUM" ->
          base + " 🟡 **中等风险**：供应商结构偏集中，建议培养第二梯队。";
      default -> base + " 🟢 **低风险**：供应商结构分散，单家断供的冲击有限。";
    };
  }

  // ==========================================================================
  // ③ 价格波动
  // ==========================================================================

  /**
   * 采购价格波动（当前期间 vs 上一等长期间）。
   *
   * @param dimension 拆解维度（SKU / SUPPLIER）
   */
  public ReportingProcurementAnalysisModels.ProcurementPriceVarianceResponse priceVariance(
      UUID tenantId, LocalDate from, LocalDate to, String dimension, String currency) {
    return tenants.run(
        tenantId,
        () -> {
          var warnings = new ArrayList<String>();
          var spec = dimensionSpec(dimension);
          var days = java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1;
          var previousTo = from.minusDays(1);
          var previousFrom = previousTo.minusDays(days - 1);

          var rows =
              sql.fetch(
                  "select " + spec.key() + " as dim_key, " + spec.label() + " as dim_label, currency,"
                      + " sum(amount) filter (where occurred_on between ? and ?) as cur_amount,"
                      + " sum(quantity) filter (where occurred_on between ? and ?) as cur_qty,"
                      + " count(*) filter (where occurred_on between ? and ?) as cur_count,"
                      + " sum(amount) filter (where occurred_on between ? and ?) as prev_amount,"
                      + " sum(quantity) filter (where occurred_on between ? and ?) as prev_qty,"
                      + " count(*) filter (where occurred_on between ? and ?) as prev_count,"
                      + " count(*) as total_count"
                      + " from reporting_procurement_analysis_purchase_fact"
                      + " where tenant_id = ? and occurred_on between ? and ? and amount is not null"
                      + (currency == null ? "" : " and currency = ?")
                      + " group by 1,2,3 order by 1,2,3",
                  currency == null
                      ? new Object[] {
                        from, to, from, to, from, to, previousFrom, previousTo, previousFrom,
                        previousTo, previousFrom, previousTo, tenantId, previousFrom, to
                      }
                      : new Object[] {
                        from, to, from, to, from, to, previousFrom, previousTo, previousFrom,
                        previousTo, previousFrom, previousTo, tenantId, previousFrom, to, currency
                      });

          var out = new ArrayList<ReportingProcurementAnalysisModels.ProcurementPriceVarianceRow>();
          var currencies = new LinkedHashMap<String, Boolean>();
          BigDecimal curTotalAmount = null;
          BigDecimal curTotalQty = null;
          BigDecimal prevTotalAmount = null;
          BigDecimal prevTotalQty = null;

          for (var row : rows) {
            var ccy = row.get(2, String.class);
            currencies.put(ccy, true);
            var curAmount = row.get(3, BigDecimal.class);
            var curQty = row.get(4, BigDecimal.class);
            var curCount = count(row.get(5, Long.class));
            var prevAmount = row.get(6, BigDecimal.class);
            var prevQty = row.get(7, BigDecimal.class);
            var prevCount = count(row.get(8, Long.class));

            curTotalAmount = add(curTotalAmount, curAmount);
            curTotalQty = add(curTotalQty, curQty);
            prevTotalAmount = add(prevTotalAmount, prevAmount);
            prevTotalQty = add(prevTotalQty, prevQty);

            var curAvg = averagePrice(curAmount, curQty);
            var prevAvg = averagePrice(prevAmount, prevQty);
            BigDecimal change = null;
            String status;
            String note;
            if (curAvg == null && prevAvg == null) {
              status = "NO_DATA";
              note = "两个期间都没有可算的均价（**不可比**，不是 0%）。";
            } else if (curAvg == null) {
              status = "CURRENT_MISSING";
              note = "当前期间没有数量 ⇒ 均价**算不出来**（不是 0）。";
            } else if (prevAvg == null) {
              status = "PREVIOUS_MISSING";
              note = "上一期间没有数量 ⇒ **环比算不出来**（不是 0%）。";
            } else if (prevAvg.signum() == 0) {
              status = "PREVIOUS_ZERO";
              note = "上一期间均价为 0 ⇒ 环比**不可比**（不是 0%）。";
            } else {
              status = "COMPARABLE";
              change =
                  curAvg.subtract(prevAvg)
                      .multiply(new BigDecimal("100"))
                      .divide(prevAvg, RATE_SCALE, RoundingMode.HALF_UP);
              note = "";
            }

            out.add(
                new ReportingProcurementAnalysisModels.ProcurementPriceVarianceRow(
                    dimension,
                    row.get(0, String.class),
                    spec.displayLabel(row.get(0, String.class), row.get(1, String.class)),
                    ccy,
                    curQty,
                    curAmount,
                    curAvg,
                    prevQty,
                    prevAmount,
                    prevAvg,
                    change,
                    count(row.get(9, Long.class)),
                    status,
                    note));
          }

          var curAvgOverall = averagePrice(curTotalAmount, curTotalQty);
          var prevAvgOverall = averagePrice(prevTotalAmount, prevTotalQty);
          BigDecimal overallChange = null;
          if (curAvgOverall != null && prevAvgOverall != null && prevAvgOverall.signum() != 0) {
            overallChange =
                curAvgOverall
                    .subtract(prevAvgOverall)
                    .multiply(new BigDecimal("100"))
                    .divide(prevAvgOverall, RATE_SCALE, RoundingMode.HALF_UP);
          }

          warnings.add(
              "环比口径：**当前期间**（"
                  + from
                  + " ~ "
                  + to
                  + "，"
                  + days
                  + " 天）对**上一等长期间**（"
                  + previousFrom
                  + " ~ "
                  + previousTo
                  + "）；均价 = 金额 ÷ 数量（**加权**，不是各行单价的平均）。");

          return new ReportingProcurementAnalysisModels.ProcurementPriceVarianceResponse(
              from,
              to,
              previousFrom,
              previousTo,
              dimension,
              new ArrayList<>(currencies.keySet()),
              out,
              overallChange,
              dataUpdatedAt(tenantId),
              DATA_UPDATED_NOTE,
              PURCHASE_CALIBER + " 均价 = 金额 ÷ 数量（加权均价）。",
              warnings);
        });
  }

  /** 加权均价：金额 ÷ 数量；任一为 null 或数量为 0 ⇒ null（**不是 0**）。 */
  private static BigDecimal averagePrice(BigDecimal amount, BigDecimal quantity) {
    if (amount == null || quantity == null || quantity.signum() == 0) return null;
    return amount.divide(quantity, MONEY_SCALE, RoundingMode.HALF_UP);
  }

  // ==========================================================================
  // ④ 到货准时率
  // ==========================================================================

  /** 到货准时率（**判定归 trade 6.3.2.2**，本模块只搬运）。 */
  public ReportingProcurementAnalysisModels.ProcurementOnTimeResponse onTime(
      UUID tenantId, LocalDate from, LocalDate to) {
    return tenants.run(
        tenantId,
        () -> {
          var warnings = new ArrayList<String>();
          var rule = ruleOf(tenantId, "ON_TIME_TARGET");
          var target = rule == null ? DEFAULT_ON_TIME_TARGET : rule.onTimeTargetPct();

          var overall =
              sql.fetchOne(
                  "select count(*) filter (where on_time_status in ('ON_TIME','LATE')) as denominator,"
                      + " count(*) filter (where on_time_status = 'ON_TIME') as on_time,"
                      + " count(*) filter (where on_time_status = 'LATE') as late,"
                      + " count(*) filter (where on_time_status = 'PENDING') as pending"
                      + " from reporting_procurement_analysis_purchase_fact"
                      + " where tenant_id = ? and occurred_on between ? and ?",
                  tenantId,
                  from,
                  to);
          var denominator = overall == null ? 0L : count(overall.get(0, Long.class));
          var onTimeCount = overall == null ? 0L : count(overall.get(1, Long.class));
          var lateCount = overall == null ? 0L : count(overall.get(2, Long.class));
          var pendingCount = overall == null ? 0L : count(overall.get(3, Long.class));
          var rate = rate(onTimeCount, denominator);

          var supplierRows =
              sql.fetch(
                  "select supplier_id, supplier_code, supplier_name,"
                      + " count(*) filter (where on_time_status in ('ON_TIME','LATE')) as denominator,"
                      + " count(*) filter (where on_time_status = 'ON_TIME') as on_time,"
                      + " count(*) filter (where on_time_status = 'LATE') as late"
                      + " from reporting_procurement_analysis_purchase_fact"
                      + " where tenant_id = ? and occurred_on between ? and ?"
                      + " group by 1,2,3 order by 4 desc,1",
                  tenantId,
                  from,
                  to);
          var ratingBySupplier = ratingBySupplier(tenantId);
          var suppliers = new ArrayList<ReportingProcurementAnalysisModels.ProcurementOnTimeSupplierRow>();
          for (var row : supplierRows) {
            var supplierId = row.get(0, UUID.class);
            var rowDenominator = count(row.get(3, Long.class));
            var rating = ratingBySupplier.get(supplierId);
            suppliers.add(
                new ReportingProcurementAnalysisModels.ProcurementOnTimeSupplierRow(
                    supplierId,
                    row.get(1, String.class),
                    row.get(2, String.class),
                    rowDenominator,
                    count(row.get(4, Long.class)),
                    count(row.get(5, Long.class)),
                    rate(count(row.get(4, Long.class)), rowDenominator),
                    rating == null ? null : rating.score(),
                    rating == null ? null : rating.grade()));
          }

          var lateRows =
              sql.fetch(
                  "select id, source_doc_no, source_ref_no, supplier_name, sku_code,"
                      + " promised_arrival_date, occurred_at, quantity, amount, currency,"
                      + " (occurred_on - promised_arrival_date) as late_days"
                      + " from reporting_procurement_analysis_purchase_fact"
                      + " where tenant_id = ? and occurred_on between ? and ? and on_time_status = 'LATE'"
                      + " order by late_days desc, id limit ?",
                  tenantId,
                  from,
                  to,
                  LATE_ROW_LIMIT);
          var late = new ArrayList<ReportingProcurementAnalysisModels.ProcurementOnTimeLateRow>();
          for (var row : lateRows) {
            late.add(
                new ReportingProcurementAnalysisModels.ProcurementOnTimeLateRow(
                    row.get(0, UUID.class),
                    row.get(1, String.class),
                    row.get(2, String.class),
                    row.get(3, String.class),
                    row.get(4, String.class),
                    row.get(5, LocalDate.class),
                    row.get(6, OffsetDateTime.class),
                    row.get(7, BigDecimal.class),
                    row.get(8, BigDecimal.class),
                    row.get(9, String.class),
                    row.get(10, Integer.class)));
          }

          if (denominator == 0) {
            warnings.add(
                "这个窗口里**没有已到货的流水** ⇒ 准时率**不可比**（不是 0%）。"
                    + "PENDING（未到货）流水**不进分母**。");
          }
          if (pendingCount > 0) {
            warnings.add(
                "有 " + pendingCount + " 条流水**尚未到货**（PENDING）—— 它们不进准时率分母，"
                    + "但它们是「还没兑现的承诺」，下钻清单里单独可见。");
          }

          return new ReportingProcurementAnalysisModels.ProcurementOnTimeResponse(
              from,
              to,
              denominator,
              onTimeCount,
              lateCount,
              pendingCount,
              rate,
              target,
              rate == null ? null : rate.compareTo(target) >= 0,
              suppliers,
              late,
              dataUpdatedAt(tenantId),
              DATA_UPDATED_NOTE,
              ON_TIME_CALIBER,
              warnings);
        });
  }

  /** 比率：分母为 0 ⇒ null（**不可比，不是 0**）。 */
  private static BigDecimal rate(long numerator, long denominator) {
    if (denominator <= 0) return null;
    return BigDecimal.valueOf(numerator)
        .multiply(new BigDecimal("100"))
        .divide(BigDecimal.valueOf(denominator), RATE_SCALE, RoundingMode.HALF_UP);
  }

  // ==========================================================================
  // ⑤ 采购周期
  // ==========================================================================

  /** 采购周期（到货日 − 下单日）。 */
  public ReportingProcurementAnalysisModels.ProcurementCycleResponse purchaseCycle(
      UUID tenantId, LocalDate from, LocalDate to) {
    return tenants.run(
        tenantId,
        () -> {
          var warnings = new ArrayList<String>();
          var rule = ruleOf(tenantId, "PURCHASE_CYCLE_TARGET");
          var target = rule == null ? DEFAULT_CYCLE_TARGET_DAYS : rule.purchaseCycleTargetDays();

          var overall =
              sql.fetchOne(
                  "select count(*) as samples, avg(cycle_days) as avg_days,"
                      + " percentile_disc(0.5) within group (order by cycle_days) as median_days,"
                      + " percentile_disc(0.9) within group (order by cycle_days) as p90_days"
                      + " from reporting_procurement_analysis_delivery_fact"
                      + " where tenant_id = ? and received_date between ? and ? and not is_void",
                  tenantId,
                  from,
                  to);
          var samples = overall == null ? 0L : count(overall.get(0, Long.class));
          var avgDays = overall == null ? null : overall.get(1, BigDecimal.class);
          var medianDays = overall == null ? null : overall.get(2, Integer.class);
          var p90Days = overall == null ? null : overall.get(3, Integer.class);

          var supplierRows =
              sql.fetch(
                  "select supplier_id, supplier_code, supplier_name, count(*) as samples,"
                      + " avg(cycle_days) as avg_days,"
                      + " percentile_disc(0.5) within group (order by cycle_days) as median_days,"
                      + " percentile_disc(0.9) within group (order by cycle_days) as p90_days,"
                      + " max(received_date) as latest_received,"
                      + " (array_agg(cycle_days order by received_date desc, id desc))[1] as latest_days"
                      + " from reporting_procurement_analysis_delivery_fact"
                      + " where tenant_id = ? and received_date between ? and ? and not is_void"
                      + " group by 1,2,3 order by 1",
                  tenantId,
                  from,
                  to);
          var suppliers = new ArrayList<ReportingProcurementAnalysisModels.ProcurementCycleSupplierRow>();
          for (var row : supplierRows) {
            suppliers.add(
                new ReportingProcurementAnalysisModels.ProcurementCycleSupplierRow(
                    row.get(0, UUID.class),
                    row.get(1, String.class),
                    row.get(2, String.class),
                    count(row.get(3, Long.class)),
                    row.get(4, BigDecimal.class),
                    row.get(5, Integer.class) == null ? null : new BigDecimal(row.get(5, Integer.class)),
                    row.get(6, Integer.class) == null ? null : new BigDecimal(row.get(6, Integer.class)),
                    row.get(8, Integer.class) == null ? null : new BigDecimal(row.get(8, Integer.class)),
                    row.get(7, LocalDate.class)));
          }

          if (samples == 0) {
            warnings.add(
                "这个窗口里**没有到货记录** ⇒ 采购周期**不可比**（不是 0 天）。"
                    + "先在 catalog 6.1.1.1 记录到货，再刷新投影。");
          }

          return new ReportingProcurementAnalysisModels.ProcurementCycleResponse(
              from,
              to,
              avgDays == null ? null : avgDays.setScale(MONEY_SCALE, RoundingMode.HALF_UP),
              medianDays == null ? null : new BigDecimal(medianDays),
              p90Days == null ? null : new BigDecimal(p90Days),
              samples,
              target,
              avgDays == null ? null : avgDays.compareTo(target) <= 0,
              suppliers,
              dataUpdatedAt(tenantId),
              DATA_UPDATED_NOTE,
              CYCLE_CALIBER,
              warnings);
        });
  }

  // ==========================================================================
  // ⑥ 采购成本占销售额比
  // ==========================================================================

  /**
   * 采购成本占销售额比（**逐币种**）。
   *
   * <p>🔴 **分母来自 13.1.1.1 已投影的 {@code reporting_metric_fact}**（SALES_AMOUNT）——
   * 本模块**不重算**销售额（卷 13 卷首「口径唯一」）。该投影还没跑 ⇒ 分母为 0 ⇒
   * 比率**不可比**（不是 0%）。
   */
  public ReportingProcurementAnalysisModels.ProcurementCostToSalesResponse costToSales(
      UUID tenantId, LocalDate from, LocalDate to) {
    return tenants.run(
        tenantId,
        () -> {
          var warnings = new ArrayList<String>();
          var rows =
              sql.fetch(
                  """
                  with purchase as (
                    select currency, sum(amount) as amount, count(*) as cnt
                    from reporting_procurement_analysis_purchase_fact
                    where tenant_id = ? and occurred_on between ? and ? and amount is not null
                    group by currency
                  ), sales as (
                    select currency, sum(value) as amount, count(*) as cnt
                    from reporting_metric_fact
                    where tenant_id = ?
                      and scope_type = 'COMPANY'
                      and legal_entity_id is null
                      and shop_id is null
                      and metric_code = 'SALES_AMOUNT'
                      and bucket_date between ? and ?
                    group by currency
                  )
                  select coalesce(p.currency, s.currency) as currency,
                         p.amount as purchase_amount, p.cnt as purchase_count,
                         s.amount as sales_amount, s.cnt as sales_count,
                         case when s.amount is null or s.amount = 0 then null
                              else round((p.amount::numeric / s.amount::numeric) * 100, 4)
                         end as ratio
                  from purchase p
                  full outer join sales s on s.currency = p.currency
                  order by 1
                  """,
                  tenantId,
                  from,
                  to,
                  tenantId,
                  from,
                  to);

          var out = new ArrayList<ReportingProcurementAnalysisModels.ProcurementCostToSalesRow>();
          boolean anySales = false;
          for (var row : rows) {
            var ccy = row.get(0, String.class);
            var purchaseAmount = row.get(1, BigDecimal.class);
            var purchaseCount = count(row.get(2, Long.class));
            var salesAmount = row.get(3, BigDecimal.class);
            var salesCount = count(row.get(4, Long.class));
            var ratio = row.get(5, BigDecimal.class);
            if (salesAmount != null) anySales = true;
            String note;
            if (salesAmount == null) {
              note =
                  "这个币种**没有销售额事实**（13.1.1.1 的投影里没有这一行）⇒ 成本占比**不可比**（不是 0%）。";
            } else if (salesAmount.signum() == 0) {
              note = "这个币种的销售额为 0 ⇒ 成本占比**不可比**（不是 0%，也不是无穷大）。";
            } else {
              note = "";
            }
            out.add(
                new ReportingProcurementAnalysisModels.ProcurementCostToSalesRow(
                    ccy,
                    purchaseAmount,
                    salesAmount,
                    ratio,
                    purchaseCount,
                    salesCount,
                    note));
          }

          if (!anySales) {
            warnings.add(
                "**销售额分母尚不可得** —— 13.1.1.1 的投影（`reporting_metric_fact` / SALES_AMOUNT）"
                    + "在这个窗口里没有事实。成本占比因此**不可比**（不是 0%）。"
                    + "本模块**不重算**销售额（口径唯一）。");
          }

          return new ReportingProcurementAnalysisModels.ProcurementCostToSalesResponse(
              from,
              to,
              anySales,
              anySales ? null : "13.1.1.1 经营总览的投影（reporting 服务，已交付）",
              anySales
                  ? null
                  : "下游 13.4 利润会少一个先行指标（蓝图 6.5.2.1：「采购成本占比是利润健康度的先行指标」）。",
              out,
              dataUpdatedAt(tenantId),
              DATA_UPDATED_NOTE,
              "采购成本占销售额比 = **同币种**的采购到货金额 ÷ **同币种**的销售额（13.1.1.1 已投影的 "
                  + "SALES_AMOUNT，本模块不重算）。" + CURRENCY_NOTE,
              warnings);
        });
  }

  // ==========================================================================
  // ⑦ 供应商对比
  // ==========================================================================

  /** 供应商对比（真分页 + 真计数）。 */
  public ReportingProcurementAnalysisModels.ProcurementSupplierComparisonResponse suppliers(
      UUID tenantId, LocalDate from, LocalDate to, String currency, int page, int size) {
    return tenants.run(
        tenantId,
        () -> {
          var warnings = new ArrayList<String>();
          var currencyFilter = currency == null ? "" : " and currency = ?";
          var args =
              currency == null
                  ? new Object[] {tenantId, from, to}
                  : new Object[] {tenantId, from, to, currency};

          var total = countOf(sql.fetchOne(
              "select count(distinct supplier_id) from reporting_procurement_analysis_purchase_fact"
                  + " where tenant_id = ? and occurred_on between ? and ? and supplier_id is not null"
                  + currencyFilter,
              args));

          // ⚠️ **按采购金额排序取一页**：排序键是聚合出来的（sum(amount)），
          //   不能直接写在 order by 的列号上 —— 先按金额排出供应商，再分页。
          var supplierIds =
              sql.fetch(
                  "select supplier_id, sum(amount) as amount"
                      + " from reporting_procurement_analysis_purchase_fact"
                      + " where tenant_id = ? and occurred_on between ? and ? and supplier_id is not null"
                      + currencyFilter
                      + " group by 1 order by 2 desc nulls last, 1"
                      + " limit ? offset ?",
                  concat(args, new Object[] {size, page * size}));

          var ratingBySupplier = ratingBySupplier(tenantId);
          var onTimeBySupplier = onTimeBySupplier(tenantId, from, to);
          var cycleBySupplier = cycleBySupplier(tenantId, from, to);
          var amountBySupplier = amountBySupplier(tenantId, from, to);
          var totalByCurrency = totalByCurrency(tenantId, from, to);

          var rows = new ArrayList<ReportingProcurementAnalysisModels.ProcurementSupplierRow>();
          for (var row : supplierIds) {
            var supplierId = row.get(0, UUID.class);
            var archive =
                sql.fetchOne(
                    "select supplier_code, supplier_name, party_status, cooperation_level,"
                        + " supply_status, country_code"
                        + " from reporting_procurement_analysis_supplier_fact"
                        + " where tenant_id = ? and supplier_id = ?",
                    tenantId,
                    supplierId);
            var rating = ratingBySupplier.get(supplierId);
            var onTime = onTimeBySupplier.get(supplierId);
            var cycle = cycleBySupplier.get(supplierId);
            var amounts = amountBySupplier.get(supplierId);

            var purchaseAmount = (BigDecimal) null;
            String purchaseCurrency = null;
            BigDecimal purchaseShare = null;
            if (currency != null && amounts != null) {
              purchaseAmount = amounts.amounts.get(currency);
              purchaseCurrency = currency;
              var denominator = totalByCurrency.get(currency);
              if (purchaseAmount != null && denominator != null && denominator.signum() != 0) {
                purchaseShare =
                    purchaseAmount
                        .multiply(new BigDecimal("100"))
                        .divide(denominator, RATE_SCALE, RoundingMode.HALF_UP);
              }
            } else if (amounts != null && amounts.amounts.size() == 1) {
              var entry = amounts.amounts.entrySet().iterator().next();
              purchaseAmount = entry.getValue();
              purchaseCurrency = entry.getKey();
              var denominator = totalByCurrency.get(entry.getKey());
              if (purchaseAmount != null && denominator != null && denominator.signum() != 0) {
                purchaseShare =
                    purchaseAmount
                        .multiply(new BigDecimal("100"))
                        .divide(denominator, RATE_SCALE, RoundingMode.HALF_UP);
              }
            }

            var note = new StringBuilder();
            if (amounts == null || amounts.amounts.isEmpty()) {
              note.append("这个窗口里没有该供应商的**带金额**采购流水（不是「采购金额为 0」）。");
            } else if (purchaseAmount == null) {
              note.append(
                  "该供应商涉及 "
                      + amounts.amounts.size()
                      + " 个币种，**跨币种不相加** ⇒ 请用 `currency` 参数筛选后再看金额与占比。");
            }
            if (rating == null) {
              note.append(" 评级：**未评级**（6.1.1.3 还没有这家或这一期的评级），不是 0 分。");
            }

            rows.add(
                new ReportingProcurementAnalysisModels.ProcurementSupplierRow(
                    supplierId,
                    archive == null ? "" : nullToEmpty(archive.get(0, String.class)),
                    archive == null ? "" : nullToEmpty(archive.get(1, String.class)),
                    archive == null ? "" : nullToEmpty(archive.get(2, String.class)),
                    archive == null ? "" : nullToEmpty(archive.get(3, String.class)),
                    archive == null ? "" : nullToEmpty(archive.get(4, String.class)),
                    archive == null ? null : archive.get(5, String.class),
                    purchaseAmount,
                    purchaseCurrency,
                    purchaseShare,
                    amounts == null ? 0L : amounts.factCount,
                    onTime == null ? null : onTime.rate(),
                    cycle == null ? null : cycle.averageDays(),
                    rating == null ? null : rating.score(),
                    rating == null ? null : rating.grade(),
                    rating == null ? null : rating.periodStart(),
                    rating == null ? null : rating.periodEnd(),
                    note.toString()));
          }

          if (currency == null && totalByCurrency.size() > 1) {
            warnings.add(
                "本次结果涉及 "
                    + totalByCurrency.size()
                    + " 个币种，**跨币种的采购金额与占比不可加** ⇒ "
                    + "请用 `currency` 参数筛选后再看金额与占比。");
          }

          return new ReportingProcurementAnalysisModels.ProcurementSupplierComparisonResponse(
              from,
              to,
              rows,
              total,
              page,
              size,
              dataUpdatedAt(tenantId),
              DATA_UPDATED_NOTE,
              PURCHASE_CALIBER,
              warnings);
        });
  }

  // ==========================================================================
  // 下钻
  // ==========================================================================

  /** 下钻到到货流水（真分页 + 真计数）。 */
  public ReportingProcurementAnalysisModels.ProcurementDrilldownResponse drilldown(
      UUID tenantId, LocalDate from, LocalDate to, String currency, int page, int size) {
    return tenants.run(
        tenantId,
        () -> {
          var currencyFilter = currency == null ? "" : " and currency = ?";
          var args =
              currency == null
                  ? new Object[] {tenantId, from, to}
                  : new Object[] {tenantId, from, to, currency};

          var total = countOf(sql.fetchOne(
              "select count(*) from reporting_procurement_analysis_purchase_fact"
                  + " where tenant_id = ? and occurred_on between ? and ?" + currencyFilter,
              args));

          var rows =
              sql.fetch(
                  "select id, source_doc_no, source_ref_no, occurred_on, occurred_at,"
                      + " supplier_id, supplier_code, supplier_name, sku_code, sku_name,"
                      + " category_code, shop_code, quantity, unit_price, amount, currency,"
                      + " promised_arrival_date, on_time_status"
                      + " from reporting_procurement_analysis_purchase_fact"
                      + " where tenant_id = ? and occurred_on between ? and ?" + currencyFilter
                      + " order by occurred_on desc, id desc limit ? offset ?",
                  concat(args, new Object[] {size, page * size}));

          var out = new ArrayList<ReportingProcurementAnalysisModels.ProcurementDrilldownRow>();
          for (var row : rows) {
            var onTimeStatus = row.get(17, String.class);
            var note = switch (onTimeStatus == null ? "" : onTimeStatus) {
              case "ON_TIME" -> "准时到货。";
              case "LATE" -> "逾期到货（判定归 trade 6.3.2.2）。";
              case "PENDING" -> "尚未到货（不进准时率分母）。";
              default -> "上游未给准时判定。";
            };
            if (row.get(14, BigDecimal.class) == null) {
              note += " ⚠️ 这条流水**没有金额 / 币种** —— 金额是「—」，**不在任何合计里**（不是 0 元）。";
            }
            out.add(
                new ReportingProcurementAnalysisModels.ProcurementDrilldownRow(
                    row.get(0, UUID.class),
                    row.get(1, String.class),
                    row.get(2, String.class),
                    row.get(3, LocalDate.class),
                    row.get(4, OffsetDateTime.class),
                    row.get(5, UUID.class),
                    row.get(6, String.class),
                    row.get(7, String.class),
                    row.get(8, String.class),
                    row.get(9, String.class),
                    row.get(10, String.class),
                    row.get(11, String.class),
                    row.get(12, BigDecimal.class),
                    row.get(13, BigDecimal.class),
                    row.get(14, BigDecimal.class),
                    row.get(15, String.class),
                    row.get(16, LocalDate.class),
                    onTimeStatus,
                    note));
          }

          return new ReportingProcurementAnalysisModels.ProcurementDrilldownResponse(
              from,
              to,
              out,
              total,
              page,
              size,
              dataUpdatedAt(tenantId),
              DATA_UPDATED_NOTE,
              PURCHASE_CALIBER,
              List.of());
        });
  }

  // ==========================================================================
  // 阈值规则
  // ==========================================================================

  /** 阈值规则（**没有配置时给默认值**，并标明是默认值）。 */
  public List<ReportingProcurementAnalysisModels.ProcurementAnalysisRule> rules(UUID tenantId) {
    return tenants.run(
        tenantId,
        () -> {
          var out = new ArrayList<ReportingProcurementAnalysisModels.ProcurementAnalysisRule>();
          for (var type : List.of("CONCENTRATION_RISK", "ON_TIME_TARGET", "PURCHASE_CYCLE_TARGET")) {
            var rule = ruleOf(tenantId, type);
            if (rule == null) {
              out.add(defaultRule(type));
            } else {
              out.add(rule);
            }
          }
          return out;
        });
  }

  private static ReportingProcurementAnalysisModels.ProcurementAnalysisRule defaultRule(String type) {
    return switch (type) {
      case "CONCENTRATION_RISK" ->
          new ReportingProcurementAnalysisModels.ProcurementAnalysisRule(
              type,
              DEFAULT_TOP_N,
              DEFAULT_TOP_SHARE_PCT,
              DEFAULT_SINGLE_SUPPLIER_SHARE_PCT,
              null,
              null,
              1,
              true,
              null);
      case "ON_TIME_TARGET" ->
          new ReportingProcurementAnalysisModels.ProcurementAnalysisRule(
              type, null, null, null, DEFAULT_ON_TIME_TARGET, null, 1, true, null);
      default ->
          new ReportingProcurementAnalysisModels.ProcurementAnalysisRule(
              type, null, null, null, null, DEFAULT_CYCLE_TARGET_DAYS, 1, true, null);
    };
  }

  /** 改阈值（**只改本模块自己的规则表**，不写任何上游口径）。 */
  public ReportingProcurementAnalysisModels.ProcurementAnalysisRule updateRule(
      UUID tenantId,
      String ruleType,
      ReportingProcurementAnalysisModels.ProcurementAnalysisRuleInput input) {
    return tenants.run(
        tenantId,
        () -> {
          var existing = ruleOf(tenantId, ruleType);
          var topN =
              input.concentrationTopN() != null
                  ? input.concentrationTopN()
                  : existing == null ? null : existing.concentrationTopN();
          var topShare =
              input.concentrationTopSharePct() != null
                  ? input.concentrationTopSharePct()
                  : existing == null ? null : existing.concentrationTopSharePct();
          var singleSupplierShare =
              input.singleSupplierSharePct() != null
                  ? input.singleSupplierSharePct()
                  : existing == null ? null : existing.singleSupplierSharePct();
          var onTimeTarget =
              input.onTimeTargetPct() != null
                  ? input.onTimeTargetPct()
                  : existing == null ? null : existing.onTimeTargetPct();
          var cycleTarget =
              input.purchaseCycleTargetDays() != null
                  ? input.purchaseCycleTargetDays()
                  : existing == null ? null : existing.purchaseCycleTargetDays();
          var minHistory =
              input.minHistoryDays() != null
                  ? input.minHistoryDays()
                  : existing == null ? 1 : existing.minHistoryDays();
          var enabled =
              input.enabled() != null ? input.enabled() : existing == null || existing.enabled();

          // ⚠️ **先做一次与 DB CHECK 同形的校验**：数据库会挡住非法组合，
          //   但那会变成一条 500 —— 用户看到的是「服务器错了」，而真相是「你填的阈值不成套」。
          validateRule(ruleType, topN, topShare, singleSupplierShare, onTimeTarget, cycleTarget);

          sql.execute(
              """
              insert into reporting_procurement_analysis_rule
                (id,tenant_id,rule_type,concentration_top_n,concentration_top_share_pct,
                 single_supplier_share_pct,
                 on_time_target_pct,purchase_cycle_target_days,min_history_days,enabled,updated_at)
              values (?,?,?,?,?,?,?,?,?,?,now())
              on conflict (tenant_id,rule_type)
              do update set concentration_top_n = excluded.concentration_top_n,
                            concentration_top_share_pct = excluded.concentration_top_share_pct,
                            single_supplier_share_pct = excluded.single_supplier_share_pct,
                            on_time_target_pct = excluded.on_time_target_pct,
                            purchase_cycle_target_days = excluded.purchase_cycle_target_days,
                            min_history_days = excluded.min_history_days,
                            enabled = excluded.enabled,
                            updated_at = now()
              """,
              Ids.next(),
              tenantId,
              ruleType,
              topN,
              topShare,
              singleSupplierShare,
              onTimeTarget,
              cycleTarget,
              minHistory,
              enabled);
          return ruleOf(tenantId, ruleType);
        });
  }

  /**
   * 与 DB CHECK 同形的规则校验（**在服务层先挡一道**，给出人话而不是 500）。
   *
   * <p>⚠️ 三类规则的字段**互斥**：一条规则只带自己那一套阈值。
   */
  private static void validateRule(
      String ruleType,
      Integer topN,
      BigDecimal topShare,
      BigDecimal singleShare,
      BigDecimal onTimeTarget,
      BigDecimal cycleTarget) {
    switch (ruleType) {
      case "CONCENTRATION_RISK" -> {
        require(topN != null && topN >= 1, "集中度规则必须给 concentrationTopN（≥ 1）");
        require(
            topShare != null && topShare.signum() > 0 && topShare.compareTo(new BigDecimal("100")) <= 0,
            "集中度规则必须给 concentrationTopSharePct（0 ~ 100 之间）");
        require(
            singleShare != null
                && singleShare.signum() > 0
                && singleShare.compareTo(new BigDecimal("100")) <= 0,
            "集中度规则必须给 singleSupplierSharePct（0 ~ 100 之间）");
        require(onTimeTarget == null, "集中度规则不带准时率目标（那是另一类规则）");
        require(cycleTarget == null, "集中度规则不带采购周期目标（那是另一类规则）");
      }
      case "ON_TIME_TARGET" -> {
        require(
            onTimeTarget != null
                && onTimeTarget.signum() >= 0
                && onTimeTarget.compareTo(new BigDecimal("100")) <= 0,
            "准时率规则必须给 onTimeTargetPct（0 ~ 100 之间）");
        require(topN == null && topShare == null && singleShare == null, "准时率规则不带集中度阈值");
        require(cycleTarget == null, "准时率规则不带采购周期目标（那是另一类规则）");
      }
      case "PURCHASE_CYCLE_TARGET" -> {
        require(
            cycleTarget != null && cycleTarget.signum() >= 0,
            "采购周期规则必须给 purchaseCycleTargetDays（≥ 0）");
        require(topN == null && topShare == null && singleShare == null, "采购周期规则不带集中度阈值");
        require(onTimeTarget == null, "采购周期规则不带准时率目标（那是另一类规则）");
      }
      default ->
          throw new org.springframework.web.server.ResponseStatusException(
              org.springframework.http.HttpStatus.BAD_REQUEST, "未知的规则类型：" + ruleType);
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.BAD_REQUEST, message);
    }
  }

  /** 读一条规则（**没有就是 null**，由调用方给默认值）。 */
  private ReportingProcurementAnalysisModels.ProcurementAnalysisRule ruleOf(
      UUID tenantId, String ruleType) {
    var row =
        sql.fetchOne(
            "select concentration_top_n, concentration_top_share_pct, single_supplier_share_pct,"
                + " on_time_target_pct, purchase_cycle_target_days, min_history_days, enabled, updated_at"
                + " from reporting_procurement_analysis_rule where tenant_id = ? and rule_type = ?",
            tenantId,
            ruleType);
    if (row == null) return null;
    return new ReportingProcurementAnalysisModels.ProcurementAnalysisRule(
        ruleType,
        row.get(0, Integer.class),
        row.get(1, BigDecimal.class),
        row.get(2, BigDecimal.class),
        row.get(3, BigDecimal.class),
        row.get(4, BigDecimal.class),
        row.get(5, Integer.class) == null ? 1 : row.get(5, Integer.class),
        row.get(6, Boolean.class) == null || row.get(6, Boolean.class),
        row.get(7, OffsetDateTime.class));
  }

  // ==========================================================================
  // 私有查询
  // ==========================================================================

  /** 一个供应商的评级快照。 */
  private record RatingSnapshot(
      BigDecimal score,
      String grade,
      LocalDate periodStart,
      LocalDate periodEnd) {}

  /** 供应商评级（按 supplier_id 索引）。 */
  private Map<UUID, RatingSnapshot> ratingBySupplier(UUID tenantId) {
    var map = new HashMap<UUID, RatingSnapshot>();
    var rows =
        sql.fetch(
            "select supplier_id, rating_score, rating_grade, rating_period_start, rating_period_end"
                + " from reporting_procurement_analysis_supplier_fact"
                + " where tenant_id = ? and rating_score is not null",
            tenantId);
    for (var row : rows) {
      var supplierId = row.get(0, UUID.class);
      if (supplierId == null) continue;
      map.put(
          supplierId,
          new RatingSnapshot(
              row.get(1, BigDecimal.class),
              row.get(2, String.class),
              row.get(3, LocalDate.class),
              row.get(4, LocalDate.class)));
    }
    return map;
  }

  /** 一个供应商的准时率。 */
  private record OnTimeSnapshot(long denominator, long onTime, BigDecimal rate) {}

  /** 逐供应商准时率（**同一批采购流水**上算）。 */
  private Map<UUID, OnTimeSnapshot> onTimeBySupplier(UUID tenantId, LocalDate from, LocalDate to) {
    var map = new HashMap<UUID, OnTimeSnapshot>();
    var rows =
        sql.fetch(
            "select supplier_id,"
                + " count(*) filter (where on_time_status in ('ON_TIME','LATE')) as denominator,"
                + " count(*) filter (where on_time_status = 'ON_TIME') as on_time"
                + " from reporting_procurement_analysis_purchase_fact"
                + " where tenant_id = ? and occurred_on between ? and ? and supplier_id is not null"
                + " group by 1",
            tenantId,
            from,
            to);
    for (var row : rows) {
      var supplierId = row.get(0, UUID.class);
      if (supplierId == null) continue;
      var denominator = count(row.get(1, Long.class));
      var onTime = count(row.get(2, Long.class));
      map.put(supplierId, new OnTimeSnapshot(denominator, onTime, rate(onTime, denominator)));
    }
    return map;
  }

  /** 一个供应商的采购周期。 */
  private record CycleSnapshot(long samples, BigDecimal averageDays) {}

  /** 逐供应商采购周期。 */
  private Map<UUID, CycleSnapshot> cycleBySupplier(UUID tenantId, LocalDate from, LocalDate to) {
    var map = new HashMap<UUID, CycleSnapshot>();
    var rows =
        sql.fetch(
            "select supplier_id, count(*) as samples, avg(cycle_days) as avg_days"
                + " from reporting_procurement_analysis_delivery_fact"
                + " where tenant_id = ? and received_date between ? and ? and not is_void"
                + " group by 1",
            tenantId,
            from,
            to);
    for (var row : rows) {
      var supplierId = row.get(0, UUID.class);
      if (supplierId == null) continue;
      map.put(
          supplierId,
          new CycleSnapshot(
              count(row.get(1, Long.class)),
              row.get(2, BigDecimal.class) == null
                  ? null
                  : row.get(2, BigDecimal.class).setScale(MONEY_SCALE, RoundingMode.HALF_UP)));
    }
    return map;
  }

  /** 一个供应商的逐币种采购金额。 */
  private record AmountSnapshot(Map<String, BigDecimal> amounts, long factCount) {}

  /** 逐供应商逐币种采购金额。 */
  private Map<UUID, AmountSnapshot> amountBySupplier(UUID tenantId, LocalDate from, LocalDate to) {
    var map = new HashMap<UUID, AmountSnapshot>();
    var rows =
        sql.fetch(
            "select supplier_id, currency, sum(amount) as amount, count(*) as fact_count"
                + " from reporting_procurement_analysis_purchase_fact"
                + " where tenant_id = ? and occurred_on between ? and ? and amount is not null"
                + " group by 1,2",
            tenantId,
            from,
            to);
    for (var row : rows) {
      var supplierId = row.get(0, UUID.class);
      if (supplierId == null) continue;
      var currency = row.get(1, String.class);
      var amount = row.get(2, BigDecimal.class);
      var snapshot = map.get(supplierId);
      if (snapshot == null) {
        snapshot = new AmountSnapshot(new LinkedHashMap<>(), 0L);
        map.put(supplierId, snapshot);
      }
      snapshot.amounts.put(currency, amount);
      snapshot = new AmountSnapshot(snapshot.amounts, snapshot.factCount + count(row.get(3, Long.class)));
      map.put(supplierId, snapshot);
    }
    return map;
  }

  /** 逐币种的窗口合计（占比的分母）。 */
  private Map<String, BigDecimal> totalByCurrency(UUID tenantId, LocalDate from, LocalDate to) {
    var map = new LinkedHashMap<String, BigDecimal>();
    var rows =
        sql.fetch(
            "select currency, sum(amount) as amount"
                + " from reporting_procurement_analysis_purchase_fact"
                + " where tenant_id = ? and occurred_on between ? and ? and amount is not null"
                + " group by 1",
            tenantId,
            from,
            to);
    for (var row : rows) {
      map.put(row.get(0, String.class), row.get(1, BigDecimal.class));
    }
    return map;
  }

  /** 最近一次投影更新时刻（**没有投影过时是 null**，不是「今天」）。 */
  private OffsetDateTime dataUpdatedAt(UUID tenantId) {
    var row =
        sql.fetchOne(
            "select max(updated_at) from ("
                + " select max(updated_at) as updated_at from reporting_procurement_analysis_purchase_fact where tenant_id = ?"
                + " union all"
                + " select max(updated_at) from reporting_procurement_analysis_delivery_fact where tenant_id = ?"
                + " union all"
                + " select max(updated_at) from reporting_procurement_analysis_supplier_fact where tenant_id = ?"
                + ") t",
            tenantId,
            tenantId,
            tenantId);
    return row == null ? null : row.get(0, OffsetDateTime.class);
  }

  // ==========================================================================
  // 小工具
  // ==========================================================================

  /** 期间表达式（DAY / WEEK / MONTH）。 */
  private static String periodExpression(String granularity) {
    return switch (granularity) {
      case "WEEK" -> "to_char(date_trunc('week', occurred_on::timestamp)::date,'YYYY-MM-DD')";
      case "MONTH" -> "to_char(occurred_on,'YYYY-MM')";
      default -> "to_char(occurred_on,'YYYY-MM-DD')";
    };
  }

  /** 拆解维度的 SQL 片段。 */
  private record DimensionSpec(String key, String label) {
    String displayLabel(String key, String label) {
      if (label != null && !label.isBlank()) return label;
      return key == null || key.isBlank() ? "（未归属）" : key;
    }
  }

  private static DimensionSpec dimensionSpec(String dimension) {
    return switch (dimension) {
      case "SUPPLIER" ->
          new DimensionSpec(
              "supplier_id",
              "case when supplier_id is null then '' else coalesce(supplier_name,supplier_code,'') end");
      case "CATEGORY" -> new DimensionSpec("category_code", "category_code");
      case "SHOP" -> new DimensionSpec("shop_code", "shop_code");
      case "SKU" -> new DimensionSpec("sku_code", "sku_name");
      default -> new DimensionSpec("supplier_id", "supplier_name");
    };
  }

  private static long count(Long value) {
    return value == null ? 0L : value;
  }

  /** 从 {@code select count(*)} 那一行取计数（**没有行就是 0**）。 */
  private static long countOf(org.jooq.Record row) {
    if (row == null) return 0L;
    return count(row.get(0, Long.class));
  }

  private static String nullToEmpty(String value) {
    return value == null ? "" : value;
  }

  private static BigDecimal add(BigDecimal current, BigDecimal delta) {
    if (delta == null) return current;
    return current == null ? delta : current.add(delta);
  }

  private static Object[] concat(Object[] a, Object[] b) {
    var out = new Object[a.length + b.length];
    System.arraycopy(a, 0, out, 0, a.length);
    System.arraycopy(b, 0, out, a.length, b.length);
    return out;
  }

  /** 「数据截至」的说明。 */
  static final String DATA_UPDATED_NOTE =
      "数据是**投影**，不是实时的；卷 13 卷首加粗句「数据延迟要显示」。"
          + "与业务系统冲突时以业务系统为准（trade / catalog）。";
}
