package com.starcon.reporting;

import com.starcon.tenancy.Ids;
import com.starcon.tenancy.TenantTransactions;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Service;

/**
 * 13.5.1.1 库存分析的**读模型**（蓝图「输出」句：库存分析报表 + 图表 + 下钻）。
 *
 * <p>⚠️ **六件指标，一个口径**：
 *
 * <ol>
 *   <li><b>库存金额结构</b> —— 在手数量 × **catalog 单位成本**（成本口径），按仓库 / 类目 / 店铺 / SKU 拆解；</li>
 *   <li><b>周转率</b> —— 销售出库**成本** ÷ 平均库存金额（**成本口径**）；</li>
 *   <li><b>库龄分布</b> —— warehouse 7.6.1.1 的分桶判定，本模块只搬运；</li>
 *   <li><b>缺货与超储</b> —— warehouse 7.1.1.1 的判定标记，本模块只搬运；</li>
 *   <li><b>ABC 分类</b> —— 按**库存金额**的累计占比切 A / B / C；</li>
 *   <li><b>呆滞占比</b> —— warehouse 7.6.1.1 的呆滞可用量 ÷ 总可用量。</li>
 * </ol>
 *
 * <p>🔴 **三条硬纪律贯穿本类**：
 *
 * <ol>
 *   <li><b>先汇总再相除</b>。周转率 = Σ 销售出库成本 ÷ Σ 平均库存金额，
 *       **不是**对逐 SKU 的周转率求平均（那会让小 SKU 的极端值主导整个数字）。</li>
 *   <li><b>分母为 0 ⇒ null（不可比），不是 0</b>。「周转率 0.00」与「这一格算不出来」
 *       在报表里长得一模一样，含义却完全相反。</li>
 *   <li><b>多币种绝不相加</b>。每一行带自己的币种；跨币种的合计不存在。</li>
 * </ol>
 */
@Service
public class ReportingInventoryAnalysisService {

  /** 金额刻度（对齐 {@code numeric(24,4)}）。 */
  static final int MONEY_SCALE = 4;

  /** 数量刻度（对齐 {@code numeric(19,6)}）。 */
  static final int QUANTITY_SCALE = 6;

  /** 比率刻度（周���率保留 4 位，够用且不引入浮点）。 */
  static final int RATE_SCALE = 4;

  /** 默认 ABC 阈值：A 类累计金额占比上限（百分数）。 */
  static final BigDecimal DEFAULT_A_CUM_PCT = new BigDecimal("80");

  /** 默认 ABC 阈值：B 类累计金额占比上限（百分数）。 */
  static final BigDecimal DEFAULT_B_CUM_PCT = new BigDecimal("95");

  /** 默认周转目标（次）。 */
  static final BigDecimal DEFAULT_TARGET_TURNOVER = new BigDecimal("4");

  private final DSLContext sql;
  private final TenantTransactions tenants;

  public ReportingInventoryAnalysisService(DSLContext sql, TenantTransactions tenants) {
    this.sql = sql;
    this.tenants = tenants;
  }

  // ==========================================================================
  // ① 库存金额结构
  // ==========================================================================

  /**
   * 库存金额结构。
   *
   * @param dimension 拆解维度（ALL / WAREHOUSE / CATEGORY / SHOP / SKU）
   * @param currency 币种过滤（空 ⇒ 全部；**绝不跨币种相加**）
   * @param asOf 快照日（空 ⇒ 取该租户最新的一张快照）
   */
  public ReportingInventoryAnalysisModels.InventoryStructureResponse structure(
      UUID tenantId, String dimension, String currency, LocalDate asOf) {
    return tenants.run(
        tenantId,
        () -> {
          var spec = dimensionSpec(dimension);
          var day = asOf == null ? latestAsOf(tenantId) : asOf;
          var warnings = new ArrayList<String>();
          if (day == null) {
            return new ReportingInventoryAnalysisModels.InventoryStructureResponse(
                null,
                spec.name(),
                List.of(),
                List.of(),
                null,
                null,
                0,
                BigDecimal.ZERO,
                null,
                "尚未投影任何库存事实 —— 先刷新投影（POST /reporting/inventory-analysis/refresh）。",
                COST_CALIBER,
                warnings);
          }

          var rows =
              sql.fetch(
                  "select " + spec.key() + " as dim_key, " + spec.label() + " as dim_label, cost_currency,"
                      + " sum(inventory_value) as inv_value, sum(on_hand) as on_hand, sum(available) as available,"
                      + " count(distinct sku_code) as sku_count,"
                      + " count(*) filter (where inventory_value is null) as cost_missing_rows,"
                      + " coalesce(sum(on_hand) filter (where inventory_value is null),0) as cost_missing_qty,"
                      + " count(*) filter (where zero_stock) as zero_rows,"
                      + " count(*) filter (where overstock) as over_rows,"
                      + " coalesce(sum(greatest(on_hand - coalesce(max_on_hand,0),0)) filter (where overstock),0) as over_qty"
                      + " from reporting_inventory_analysis_position"
                      + " where tenant_id = ? and as_of = ? and inventory_value is not null"
                      + (currency == null ? "" : " and cost_currency = ?")
                      + " group by 1,2,3 order by 1",
                  currency == null
                      ? new Object[] {tenantId, day}
                      : new Object[] {tenantId, day, currency});

          var totals =
              sql.fetch(
                  "select cost_currency, sum(inventory_value) as inv_value, sum(on_hand) as on_hand,"
                      + " sum(available) as available, count(distinct sku_code) as sku_count,"
                      + " count(*) filter (where inventory_value is null) as cost_missing_rows,"
                      + " coalesce(sum(on_hand) filter (where inventory_value is null),0) as cost_missing_qty"
                      + " from reporting_inventory_analysis_position"
                      + " where tenant_id = ? and as_of = ? and inventory_value is not null"
                      + (currency == null ? "" : " and cost_currency = ?")
                      + " group by 1 order by 1",
                  currency == null
                      ? new Object[] {tenantId, day}
                      : new Object[] {tenantId, day, currency});

          // ⚠️ **缺失成本的计数单独查**：上面的结构查询只取「算得出金额」的行（金额是币种维度的），
          //   没有维护成本的 SKU 必须在这里被单独数出来 —— 否则「有 N 个 SKU 的金额算不出来」
          //   这条最要紧的提示会跟着过滤条件一起消失。
          var missing =
              sql.fetchOne(
                  "select count(distinct sku_code) filter (where inventory_value is null) as sku_count,"
                      + " coalesce(sum(on_hand) filter (where inventory_value is null),0) as quantity"
                      + " from reporting_inventory_analysis_position where tenant_id = ? and as_of = ?",
                  tenantId,
                  day);
          var missingSkuCount =
              missing == null || missing.get(0, Long.class) == null ? 0L : missing.get(0, Long.class);
          var missingQuantity = missing == null ? BigDecimal.ZERO : missing.get(1, BigDecimal.class);
          if (missingQuantity == null) missingQuantity = BigDecimal.ZERO;

          // 逐币种合计（**绝不跨币种相加**）
          var currencyTotals = new ArrayList<ReportingInventoryAnalysisModels.InventoryCurrencyTotal>();
          var grandTotal = new BigDecimal("0");
          var currencyCount = 0;
          var totalMissingRows = 0L;
          var totalMissingQty = BigDecimal.ZERO;
          for (var row : totals) {
            currencyCount++;
            var value = row.get(1, BigDecimal.class);
            if (value != null) grandTotal = grandTotal.add(value);
            var missingRows = row.get(5, Long.class) == null ? 0L : row.get(5, Long.class);
            totalMissingRows += missingRows;
            var missingQty = row.get(6, BigDecimal.class);
            if (missingQty != null) totalMissingQty = totalMissingQty.add(missingQty);
            currencyTotals.add(
                new ReportingInventoryAnalysisModels.InventoryCurrencyTotal(
                    row.get(0, String.class),
                    value,
                    row.get(2, BigDecimal.class),
                    row.get(3, BigDecimal.class),
                    row.get(4, Long.class) == null ? 0L : row.get(4, Long.class),
                    missingRows,
                    missingQty));
          }
          if (currencyCount > 1) {
            warnings.add(
                "本次结果含 "
                    + currencyCount
                    + " 个币种，**跨币种的合计不存在** —— 页面上的「总计」只在单币种时显示。");
          }
          if (missingSkuCount > 0) {
            warnings.add(
                "有 "
                    + missingSkuCount
                    + " 个 SKU **没有维护成本单价**（catalog 5.2.1.2，在手数量 "
                    + missingQuantity.toPlainString()
                    + "），它们的库存金额与币种都是「—」，**不在任何合计里**（不是 0 元）。");
          }

          // 逐维度的占比（分母 = 同币种的合计；**先汇总再相除**）
          var perCurrencyTotal = new HashMap<String, BigDecimal>();
          for (var total : currencyTotals) {
            if (total.inventoryValue() != null) {
              perCurrencyTotal.put(total.currency(), total.inventoryValue());
            }
          }
          var structureRows = new ArrayList<ReportingInventoryAnalysisModels.InventoryStructureRow>();
          for (var row : rows) {
            var rowCurrency = row.get(2, String.class);
            var value = row.get(3, BigDecimal.class);
            var denominator = perCurrencyTotal.get(rowCurrency);
            BigDecimal share = null;
            if (value != null && denominator != null && denominator.signum() != 0) {
              share = value.multiply(new BigDecimal("100")).divide(denominator, RATE_SCALE, RoundingMode.HALF_UP);
            }
            var missingRows = row.get(7, Long.class) == null ? 0L : row.get(7, Long.class);
            structureRows.add(
                new ReportingInventoryAnalysisModels.InventoryStructureRow(
                    spec.name(),
                    row.get(0, String.class),
                    spec.displayLabel(row.get(0, String.class), row.get(1, String.class)),
                    rowCurrency,
                    value,
                    row.get(4, BigDecimal.class),
                    row.get(5, BigDecimal.class),
                    row.get(6, Long.class) == null ? 0L : row.get(6, Long.class),
                    share,
                    value != null,
                    row.get(9, Long.class) == null ? 0L : row.get(9, Long.class),
                    row.get(10, Long.class) == null ? 0L : row.get(10, Long.class),
                    row.get(11, BigDecimal.class),
                    missingRows > 0
                        ? "本行有 " + missingRows + " 行库存没有维护成本，金额是「已知部分合计」。"
                        : ""));
          }

          return new ReportingInventoryAnalysisModels.InventoryStructureResponse(
              day,
              spec.name(),
              structureRows,
              currencyTotals,
              currencyCount == 1 ? grandTotal : null,
              currencyCount == 1 && !currencyTotals.isEmpty() ? currencyTotals.get(0).currency() : null,
              missingSkuCount,
              missingQuantity,
              dataUpdatedAt(tenantId),
              DATA_UPDATED_NOTE,
              COST_CALIBER,
              warnings);
        });
  }

  // ==========================================================================
  // ② 周转率（成本口径）
  // ==========================================================================

  /**
   * 周转率 + 趋势。
   *
   * @param granularity 期间粒度（DAY / WEEK / MONTH）
   * @param dimension 拆解维度（ALL / WAREHOUSE / CATEGORY / SHOP / SKU）
   */
  public ReportingInventoryAnalysisModels.InventoryTurnoverResponse turnover(
      UUID tenantId,
      LocalDate from,
      LocalDate to,
      String granularity,
      String dimension,
      String currency) {
    return tenants.run(
        tenantId,
        () -> {
          var spec = dimensionSpec(dimension);
          var warnings = new ArrayList<String>();
          var rule = ruleOf(tenantId, "TURNOVER_TARGET");
          var target = rule == null ? DEFAULT_TARGET_TURNOVER : rule.targetTurnover();

          var closingDate = latestAsOf(tenantId, to);
          var openingDate = latestAsOf(tenantId, from);
          if (closingDate == null) {
            return new ReportingInventoryAnalysisModels.InventoryTurnoverResponse(
                from,
                to,
                granularity,
                spec.name(),
                target,
                List.of(),
                List.of(),
                null,
                DATA_UPDATED_NOTE,
                TURNOVER_CALIBER,
                warnings);
          }

          var opening = valuesAt(tenantId, openingDate, spec, currency);
          var closing = valuesAt(tenantId, closingDate, spec, currency);
          var cogs =
              spec.salesAttributable()
                  ? costOfGoodsSold(tenantId, from, to, spec, currency)
                  : Map.<String, BigDecimal>of();
          var missingCounts = missingSkuCounts(tenantId, closingDate, spec, currency);

          var days = ChronoUnit.DAYS.between(from, to) + 1;
          var rows = new ArrayList<ReportingInventoryAnalysisModels.InventoryTurnoverRow>();
          for (var key : dimensionKeys(spec, opening, closing, cogs)) {
            var group = key.split("\\|", -1);
            var dimKey = group[0];
            var dimLabel = group[1];
            var cur = group[2];
            var openingValue = opening.get(dimKey + "|" + dimLabel + "|" + cur);
            var closingValue = closing.get(dimKey + "|" + dimLabel + "|" + cur);
            var cogsValue = cogs.get(dimKey + "|" + dimLabel + "|" + cur);
            rows.add(
                turnoverRow(
                    spec.name(),
                    dimKey,
                    dimLabel,
                    cur,
                    openingValue,
                    closingValue,
                    cogsValue,
                    days,
                    spec,
                    missingCounts.getOrDefault(dimKey + "|" + dimLabel + "|" + cur, 0L),
                    spec.salesAttributable()));
          }
          rows.sort(Comparator.comparing(ReportingInventoryAnalysisModels.InventoryTurnoverRow::dimensionKey));

          var trend = trend(tenantId, from, to, granularity, spec, currency);

          if (spec.name().equals("WAREHOUSE")) {
            warnings.add(
                "**周转率按仓库拆解这一格不可比**：销售订单不带仓库维度（trade 的发货仓字段恒为 null），"
                    + "而 warehouse 的出库实发量在 8.3.2.1 / 8.3.4.1 交付前拿不到 —— "
                    + "把一笔销售成本摊给某个仓库就是编造，所以这一行是「—（不可比）」，**不是 0**。");
          }
          warnings.add(
              "分子是**销售出库成本**（销量 × catalog 单位成本），分母是**成本口径的库存金额** —— "
                  + "**没有用售价**：用售价算会虚高（蓝图加粗句）。");
          warnings.add(
              "**退货（11 售后）没有从分子里冲减**：本模块只算销售出库成本，不与售后退款额对冲。");

          return new ReportingInventoryAnalysisModels.InventoryTurnoverResponse(
              from,
              to,
              granularity,
              spec.name(),
              target,
              rows,
              trend,
              dataUpdatedAt(tenantId),
              DATA_UPDATED_NOTE,
              TURNOVER_CALIBER,
              warnings);
        });
  }

  /** 一行周转率（**先汇总再相除**：分子分母都是先加总，再相除）。 */
  private ReportingInventoryAnalysisModels.InventoryTurnoverRow turnoverRow(
      String dimension,
      String dimKey,
      String dimLabel,
      String currency,
      BigDecimal openingValue,
      BigDecimal closingValue,
      BigDecimal cogsValue,
      long days,
      DimensionSpec spec,
      long costUncoveredSkuCount,
      boolean salesAttributable) {
    var note = new StringBuilder();
    BigDecimal average = null;
    String caliber;
    if (openingValue != null && closingValue != null) {
      average = openingValue.add(closingValue).divide(new BigDecimal("2"), MONEY_SCALE, RoundingMode.HALF_UP);
      caliber = "AVERAGE";
    } else if (closingValue != null) {
      average = closingValue;
      caliber = "CLOSING";
      note.append("没有期初快照，用**期末库存金额**当分母（口径比平均口径偏保守，周转率偏高）。");
    } else {
      caliber = "NONE";
      note.append("没有期末快照，**分母算不出来** ⇒ 周转率不可比（不是 0）。");
    }

    BigDecimal rate = null;
    BigDecimal turnoverDays = null;
    if (average != null && cogsValue != null) {
      if (average.signum() == 0) {
        note.append("分母（库存金额）为 0 ⇒ 周转率**不可比**（不是 0）。");
      } else {
        rate = cogsValue.divide(average, RATE_SCALE, RoundingMode.HALF_UP);
        if (cogsValue.signum() > 0) {
          // ⚠️ **用未取整的商算周转天数**（期间天数 ÷ 周转率 = 期间天数 × 平均库存 ÷ 销售成本）。
          //   若拿已取整到 4 位的周转率去除，会把取整误差带进天数里（2 ÷ 0.2222 = 9.0009 而不是 9）。
          turnoverDays =
              new BigDecimal(days)
                  .multiply(average)
                  .divide(cogsValue, RATE_SCALE, RoundingMode.HALF_UP)
                  .setScale(RATE_SCALE, RoundingMode.HALF_UP);
        }
      }
    } else if (average != null && cogsValue == null) {
      if (salesAttributable) {
        note.append("这一段**没有销售出库事实** ⇒ 分子算不出来（不是 0）。");
      } else {
        note.append(
            "销售订单**不带仓库维度**（trade 的发货仓字段恒为 null），warehouse 的出库实发量在"
                + " 8.3.2.1 / 8.3.4.1 交付前也拿不到 ⇒ 分子算不出来，**这一格不可比**（不是 0），"
                + "也不把一笔销售成本摊给某个仓库（摊了就是编造）。");
      }
    }
    if (costUncoveredSkuCount > 0) {
      note
          .append("有 ")
          .append(costUncoveredSkuCount)
          .append(" 个 SKU 没有维护成本，它们的库存与销量**两侧都不在**分子分母里"
              + "（两侧口径一致 ⇒ 周转率本身仍可比，但只覆盖有成本的 SKU）。");
    }
    return new ReportingInventoryAnalysisModels.InventoryTurnoverRow(
        dimension,
        dimKey,
        dimLabel,
        currency,
        cogsValue,
        openingValue,
        closingValue,
        average,
        rate,
        turnoverDays,
        caliber,
        costUncoveredSkuCount,
        rate != null,
        note.toString());
  }

  /**
   * 周转率趋势。
   *
   * <p>⚠️ **每个点都是一段完整的「期初 → 期末 → 分子」**，不是把总周转率摊到每个点上。
   * 某一期的期初快照缺失时，那一期**没有点**（不可比），**不是 0**。
   */
  private List<ReportingInventoryAnalysisModels.InventoryTurnoverPoint> trend(
      UUID tenantId,
      LocalDate from,
      LocalDate to,
      String granularity,
      DimensionSpec spec,
      String currency) {
    var series = valueSeries(tenantId, spec, currency);
    if (series.isEmpty()) return List.of();
    var sales =
        !spec.salesAttributable()
            ? List.<Record>of()
            : sql.fetch(
            "select date_trunc(?, sold_on::timestamp)::date as period_start, "
                + spec.key() + " as dim_key, " + spec.label() + " as dim_label, cost_currency,"
                + " sum(cost_amount) as cogs, sum(quantity) as qty,"
                + " count(distinct sku_code) as sku_count"
                + " from reporting_inventory_analysis_sales_fact"
                + " where tenant_id = ? and sold_on >= ? and sold_on <= ?"
                + (currency == null ? "" : " and cost_currency = ?")
                + " group by 1,2,3,4 order by 1",
            concat(
                new Object[] {granularity, tenantId, from, to},
                currency == null ? new Object[] {} : new Object[] {currency}));

    var salesByPeriod = new HashMap<String, BigDecimal>();
    for (var row : sales) {
      var periodStart = row.get(0, LocalDate.class);
      if (periodStart == null) continue;
      var key = row.get(1, String.class) + "|" + row.get(2, String.class) + "|" + row.get(3, String.class);
      var cogs = row.get(4, BigDecimal.class);
      if (cogs == null) continue;
      salesByPeriod.merge(periodStart + "|" + key, cogs, BigDecimal::add);
    }

    // 趋势点的维度键 = **头寸序列里出现过的** ∪ **这一段有销售的**（两侧都不能漏）。
    var trendKeys = new java.util.TreeSet<String>();
    for (var dayValues : series.values()) trendKeys.addAll(dayValues.keySet());
    for (var key : salesByPeriod.keySet()) {
      var idx = key.indexOf('|');
      trendKeys.add(key.substring(idx + 1));
    }

    var points = new ArrayList<ReportingInventoryAnalysisModels.InventoryTurnoverPoint>();
    for (var period : periods(from, to, granularity)) {
      var days = ChronoUnit.DAYS.between(period.start(), period.end()) + 1;
      var closingValue = valueAt(series, period.end());
      var openingValue = valueAt(series, period.start());
      for (var key : trendKeys) {
        var group = key.split("\\|", -1);
        var dimKey = group[0];
        var dimLabel = group[1];
        var cur = group[2];
        var composite = dimKey + "|" + dimLabel + "|" + cur;
        var closing = closingValue.get(composite);
        var opening = openingValue.get(composite);
        var cogs = salesByPeriod.get(period.start() + "|" + composite);
        if (closing == null && opening == null && cogs == null) continue;
        var row = turnoverRow(
            spec.name(),
            dimKey,
            dimLabel,
            cur,
            opening,
            closing,
            cogs,
            days,
            spec,
            0L,
            spec.salesAttributable());
        points.add(
            new ReportingInventoryAnalysisModels.InventoryTurnoverPoint(
                period.start(),
                period.end(),
                row.dimension(),
                row.dimensionKey(),
                row.dimensionLabel(),
                row.currency(),
                row.costOfGoodsSold(),
                row.openingInventoryValue(),
                row.closingInventoryValue(),
                row.averageInventoryValue(),
                row.turnoverRate(),
                row.turnoverDays(),
                row.comparable(),
                row.note()));
      }
    }
    return points;
  }

  // ==========================================================================
  // ③ 库龄分布与呆滞占比
  // ==========================================================================

  /**
   * 库龄分布 + 呆滞占比。
   *
   * @param warehouseId 仓库过滤（空 ⇒ 全租户）
   */
  public ReportingInventoryAnalysisModels.InventoryAgingResponse aging(
      UUID tenantId, LocalDate asOf, UUID warehouseId) {
    return tenants.run(
        tenantId,
        () -> {
          var warnings = new ArrayList<String>();
          var day = asOf == null ? latestAgingAsOf(tenantId) : asOf;
          if (day == null) {
            return new ReportingInventoryAnalysisModels.InventoryAgingResponse(
                null,
                List.of(),
                List.of(),
                null,
                null,
                null,
                false,
                "尚未投影任何库龄事实 —— 先刷新投影。",
                null,
                DATA_UPDATED_NOTE,
                AGING_CALIBER,
                warnings);
          }
          var scopeKey = warehouseId == null ? "" : warehouseId.toString();
          var scopeType = warehouseId == null ? "TENANT" : "WAREHOUSE";
          var rows =
              sql.fetch(
                  "select scope_type, scope_key, scope_label, metric_type, metric_key, metric_label,"
                      + " lower_days, upper_days, sku_count, bucket_count, on_hand, reserved, available,"
                      + " oldest_aging_days"
                      + " from reporting_inventory_analysis_aging_fact"
                      + " where tenant_id = ? and as_of = ? and scope_type = ? and scope_key = ?"
                      + " order by metric_type, metric_key",
                  tenantId,
                  day,
                  scopeType,
                  scopeKey);

          var buckets = new ArrayList<ReportingInventoryAnalysisModels.InventoryAgingBucketRow>();
          var slowMoving = new ArrayList<ReportingInventoryAnalysisModels.InventorySlowMovingRow>();
          var totalAvailable = BigDecimal.ZERO;
          var obsoleteAvailable = BigDecimal.ZERO;
          var obsoleteAndUp = BigDecimal.ZERO;
          var bucketTotal = BigDecimal.ZERO;
          for (var row : rows) {
            var metricType = row.get(3, String.class);
            var metricKey = row.get(4, String.class);
            var available = row.get(12, BigDecimal.class);
            if ("AGING_BUCKET".equals(metricType)) {
              bucketTotal = bucketTotal.add(available == null ? BigDecimal.ZERO : available);
              totalAvailable = totalAvailable.add(available == null ? BigDecimal.ZERO : available);
              buckets.add(
                  new ReportingInventoryAnalysisModels.InventoryAgingBucketRow(
                      metricKey,
                      row.get(5, String.class),
                      row.get(6, Integer.class),
                      row.get(7, Integer.class),
                      row.get(0, String.class),
                      row.get(1, String.class),
                      row.get(2, String.class),
                      row.get(8, Long.class) == null ? 0L : row.get(8, Long.class),
                      row.get(9, Long.class) == null ? 0L : row.get(9, Long.class),
                      row.get(10, BigDecimal.class),
                      row.get(11, BigDecimal.class),
                      available,
                      null,
                      true));
            } else {
              if ("OBSOLETE".equals(metricKey)) {
                obsoleteAvailable = available == null ? BigDecimal.ZERO : available;
              }
              if ("OBSOLETE_AND_UP".equals(metricKey)) {
                obsoleteAndUp = available == null ? BigDecimal.ZERO : available;
              }
              slowMoving.add(
                  new ReportingInventoryAnalysisModels.InventorySlowMovingRow(
                      row.get(0, String.class),
                      row.get(1, String.class),
                      row.get(2, String.class),
                      metricKey,
                      row.get(5, String.class),
                      row.get(8, Long.class) == null ? 0L : row.get(8, Long.class),
                      row.get(9, Long.class) == null ? 0L : row.get(9, Long.class),
                      available,
                      null,
                      available != null && totalAvailable.signum() != 0,
                      row.get(13, Integer.class)));
            }
          }

          // 占比：分母 = 总可用量；分母为 0 ⇒ **不可比**（不是 0%）。
          var finalBuckets = new ArrayList<ReportingInventoryAnalysisModels.InventoryAgingBucketRow>();
          for (var bucket : buckets) {
            BigDecimal share = null;
            if (bucket.available() != null && totalAvailable.signum() != 0) {
              share =
                  bucket.available()
                      .multiply(new BigDecimal("100"))
                      .divide(totalAvailable, RATE_SCALE, RoundingMode.HALF_UP);
            }
            finalBuckets.add(
                new ReportingInventoryAnalysisModels.InventoryAgingBucketRow(
                    bucket.bucket(),
                    bucket.bucketLabel(),
                    bucket.lowerDays(),
                    bucket.upperDays(),
                    bucket.scopeType(),
                    bucket.scopeKey(),
                    bucket.scopeLabel(),
                    bucket.skuCount(),
                    bucket.bucketCount(),
                    bucket.onHand(),
                    bucket.reserved(),
                    bucket.available(),
                    share,
                    share != null));
          }
          var finalSlow = new ArrayList<ReportingInventoryAnalysisModels.InventorySlowMovingRow>();
          for (var row : slowMoving) {
            BigDecimal share = null;
            if (row.available() != null && totalAvailable.signum() != 0) {
              share =
                  row.available()
                      .multiply(new BigDecimal("100"))
                      .divide(totalAvailable, RATE_SCALE, RoundingMode.HALF_UP);
            }
            finalSlow.add(
                new ReportingInventoryAnalysisModels.InventorySlowMovingRow(
                    row.scopeType(),
                    row.scopeKey(),
                    row.scopeLabel(),
                    row.status(),
                    row.statusLabel(),
                    row.skuCount(),
                    row.bucketCount(),
                    row.available(),
                    share,
                    share != null,
                    row.oldestAgingDays()));
          }

          BigDecimal obsoleteRatio = null;
          var ratioComparable = false;
          String ratioNote;
          if (totalAvailable.signum() == 0) {
            ratioNote = "总可用量为 0 ⇒ 呆滞占比**不可比**（不是 0%）。";
          } else {
            obsoleteRatio =
                obsoleteAvailable
                    .multiply(new BigDecimal("100"))
                    .divide(totalAvailable, RATE_SCALE, RoundingMode.HALF_UP);
            ratioComparable = true;
            ratioNote = "呆滞占比 = 呆滞（OBSOLETE）的可用量 ÷ 总可用量（**量口径**，不含金额）。";
          }
          if (bucketTotal.signum() == 0 && totalAvailable.signum() == 0) {
            warnings.add("这一份库龄事实里没有任何可用量 —— 先确认上游是否真的投过库龄。");
          }
          warnings.add(
              "库龄与呆滞的**判定归 warehouse 7.6.1.1**（含滞销线 / 呆滞线的阈值策略），"
                  + "本模块只搬运它的判定结果，**不重算**。");
          warnings.add("库龄分桶里**不含金额**（warehouse 的库存事实层没有成本列）⇒ 本页只报量口径的占比。");

          return new ReportingInventoryAnalysisModels.InventoryAgingResponse(
              day,
              finalBuckets,
              finalSlow,
              totalAvailable,
              obsoleteAndUp,
              obsoleteRatio,
              ratioComparable,
              ratioNote,
              dataUpdatedAt(tenantId),
              DATA_UPDATED_NOTE,
              AGING_CALIBER,
              warnings);
        });
  }

  // ==========================================================================
  // ④ 缺货与超储
  // ==========================================================================

  /** 缺货与超储（**计数按维度、金额按币种**）。 */
  public ReportingInventoryAnalysisModels.InventoryStockStatusResponse stockStatus(
      UUID tenantId, String dimension, LocalDate asOf) {
    return tenants.run(
        tenantId,
        () -> {
          var spec = dimensionSpec(dimension);
          var warnings = new ArrayList<String>();
          var day = asOf == null ? latestAsOf(tenantId) : asOf;
          if (day == null) {
            return new ReportingInventoryAnalysisModels.InventoryStockStatusResponse(
                null, spec.name(), List.of(), null, DATA_UPDATED_NOTE, STOCK_STATUS_CALIBER, warnings);
          }
          // ---- 计数与数量（**与币种无关**）⇒ 按维度聚合 ----
          var counts =
              sql.fetch(
                  "select " + spec.key() + " as dim_key, " + spec.label() + " as dim_label,"
                      + " count(distinct sku_code) as sku_count,"
                      + " count(*) filter (where zero_stock) as zero_rows,"
                      + " coalesce(sum(available) filter (where zero_stock),0) as zero_available,"
                      + " count(*) filter (where overstock) as over_rows,"
                      + " coalesce(sum(greatest(on_hand - coalesce(max_on_hand,0),0)) filter (where overstock),0) as over_qty,"
                      + " count(*) filter (where not threshold_configured) as unset_rows"
                      + " from reporting_inventory_analysis_position"
                      + " where tenant_id = ? and as_of = ? group by 1,2 order by 1",
                  tenantId,
                  day);
          // ---- 金额（**必须按币种拆开**）⇒ 按维度 × 币种聚合 ----
          var amounts =
              sql.fetch(
                  "select " + spec.key() + " as dim_key, " + spec.label() + " as dim_label, cost_currency,"
                      + " sum(inventory_value) filter (where zero_stock) as zero_value,"
                      + " sum(inventory_value) filter (where overstock) as over_value,"
                      + " sum(inventory_value) as total_value"
                      + " from reporting_inventory_analysis_position"
                      + " where tenant_id = ? and as_of = ? group by 1,2,3 order by 1,3",
                  tenantId,
                  day);

          var amountByDimension = new HashMap<String, List<ReportingInventoryAnalysisModels.InventoryStockStatusAmount>>();
          for (var row : amounts) {
            var dimKey = row.get(0, String.class);
            var list =
                amountByDimension.computeIfAbsent(
                    dimKey, k -> new ArrayList<ReportingInventoryAnalysisModels.InventoryStockStatusAmount>());
            list.add(
                new ReportingInventoryAnalysisModels.InventoryStockStatusAmount(
                    row.get(2, String.class),
                    row.get(3, BigDecimal.class),
                    row.get(4, BigDecimal.class),
                    row.get(5, BigDecimal.class)));
          }

          var out = new ArrayList<ReportingInventoryAnalysisModels.InventoryStockStatusRow>();
          for (var row : counts) {
            var dimKey = row.get(0, String.class);
            out.add(
                new ReportingInventoryAnalysisModels.InventoryStockStatusRow(
                    spec.name(),
                    dimKey,
                    spec.displayLabel(dimKey, row.get(1, String.class)),
                    row.get(2, Long.class) == null ? 0L : row.get(2, Long.class),
                    row.get(3, Long.class) == null ? 0L : row.get(3, Long.class),
                    row.get(4, BigDecimal.class),
                    row.get(5, Long.class) == null ? 0L : row.get(5, Long.class),
                    row.get(6, BigDecimal.class),
                    row.get(7, Long.class) == null ? 0L : row.get(7, Long.class),
                    amountByDimension.getOrDefault(dimKey, List.of()),
                    "缺货 = 可用量 ≤ 0；超储 = 在手量超过**已配置**的阈值"
                        + "（**未设阈值**单独计数，不与「没超储」混为一谈）。"));
          }
          warnings.add(
              "超储阈值归 **warehouse 7.2.2 库存阈值策略**所有；本模块只搬运它的判定，**不重算阈值**。");
          warnings.add(
              "**计数与数量按维度、金额按币种**：缺货 / 超储的 SKU 数与件数与币种无关，"
                  + "而金额**绝不跨币种相加**（每个币种一行）。");
          return new ReportingInventoryAnalysisModels.InventoryStockStatusResponse(
              day,
              spec.name(),
              out,
              dataUpdatedAt(tenantId),
              DATA_UPDATED_NOTE,
              STOCK_STATUS_CALIBER,
              warnings);
        });
  }

  // ==========================================================================
  // ⑤ ABC 分类
  // ==========================================================================

  /**
   * ABC 分类。
   *
   * <p>⚠️ **切分依据是库存金额的累计占比**，不是销量、也不是 SKU 数 ——
   * 库存分析要回答的是「哪些 SKU 占了大部分资金」。
   *
   * <p>⚠️ **分类口径**：一个 SKU 归到哪一类，看的是它**前面已经累计**的金额占比
   * （{@code cumBefore}）：{@code cumBefore < aCumPct ⇒ A}、{@code < bCumPct ⇒ B}、否则 C。
   * 这样「跨过阈值的那一件」归入它**跨入**的那一类（价值高的不降级）。
   */
  public ReportingInventoryAnalysisModels.InventoryAbcResponse abc(
      UUID tenantId, String currency, LocalDate asOf, int page, int size) {
    return tenants.run(
        tenantId,
        () -> {
          var warnings = new ArrayList<String>();
          var day = asOf == null ? latestAsOf(tenantId) : asOf;
          if (day == null) {
            return new ReportingInventoryAnalysisModels.InventoryAbcResponse(
                null,
                null,
                null,
                List.of(),
                List.of(),
                0,
                Math.max(page, 0),
                Math.max(size, 1),
                null,
                DATA_UPDATED_NOTE,
                ABC_CALIBER,
                warnings);
          }
          var rule = ruleOf(tenantId, "ABC_CLASS");
          var aCum = rule == null || rule.aCumPct() == null ? DEFAULT_A_CUM_PCT : rule.aCumPct();
          var bCum = rule == null || rule.bCumPct() == null ? DEFAULT_B_CUM_PCT : rule.bCumPct();

          var rows =
              sql.fetch(
                  "select sku_code, max(sku_name) as sku_name, cost_currency,"
                      + " sum(inventory_value) as inv_value, sum(on_hand) as on_hand, sum(available) as available,"
                      + " max(category_code) as category_code, max(warehouse_code) as warehouse_code,"
                      + " max(shop_code) as shop_code"
                      + " from reporting_inventory_analysis_position"
                      + " where tenant_id = ? and as_of = ? and inventory_value is not null"
                      + (currency == null ? "" : " and cost_currency = ?")
                      + " group by 1,3 order by inv_value desc, sku_code",
                  currency == null
                      ? new Object[] {tenantId, day}
                      : new Object[] {tenantId, day, currency});

          var totals =
              sql.fetch(
                  "select cost_currency, sum(inventory_value) as inv_value"
                      + " from reporting_inventory_analysis_position"
                      + " where tenant_id = ? and as_of = ? and inventory_value is not null"
                      + (currency == null ? "" : " and cost_currency = ?")
                      + " group by 1",
                  currency == null
                      ? new Object[] {tenantId, day}
                      : new Object[] {tenantId, day, currency});
          var denominatorByCurrency = new HashMap<String, BigDecimal>();
          for (var row : totals) {
            var value = row.get(1, BigDecimal.class);
            if (value != null) denominatorByCurrency.put(row.get(0, String.class), value);
          }

          // 逐币种算累计占比（**先汇总再相除**：占比的分母是该币种的全量合计）
          var cumulative = new HashMap<String, BigDecimal>();
          var abcRows = new ArrayList<ReportingInventoryAnalysisModels.InventoryAbcRow>();
          var classCount = new HashMap<String, Long>();
          var classValue = new HashMap<String, BigDecimal>();
          for (var row : rows) {
            var skuCode = row.get(0, String.class);
            var skuName = row.get(1, String.class);
            var cur = row.get(2, String.class);
            var value = row.get(3, BigDecimal.class);
            var denominator = denominatorByCurrency.get(cur);
            BigDecimal share = null;
            if (value != null && denominator != null && denominator.signum() != 0) {
              share = value.multiply(new BigDecimal("100")).divide(denominator, RATE_SCALE, RoundingMode.HALF_UP);
            }
            // 🔴 **分类用「累计到本件之后」的金额占比**（教科书口径）：
            //   累计占比 ≤ aCumPct ⇒ A；≤ bCumPct ⇒ B；其余 C。
            //   跨过阈值的那一件落在它**跨入**的那一类（不降级）。
            // ⚠️ **分类必须用未取整的累计值**：拿展示用的 4 位小数去判档，
            //   会在 80% / 95% 边界上判错一件 SKU。
            var cumValue = cumulative.getOrDefault(cur, BigDecimal.ZERO);
            var cumAfter = cumValue.add(value == null ? BigDecimal.ZERO : value);
            BigDecimal cumulativeShare = null;
            ReportingInventoryAnalysisModels.AbcClass abcClass;
            if (denominator != null && denominator.signum() != 0) {
                var exactCumPct =
                    cumAfter.multiply(new BigDecimal("100")).divide(denominator, 10, RoundingMode.HALF_UP);
                cumulativeShare = exactCumPct.setScale(RATE_SCALE, RoundingMode.HALF_UP);
                abcClass = abcClassOf(exactCumPct, aCum, bCum);
            } else {
                abcClass = abcClassOf(BigDecimal.ZERO, aCum, bCum);
            }
            cumulative.put(cur, cumAfter);
            classCount.merge(cur + "|" + abcClass.name(), 1L, Long::sum);
            if (value != null) {
              classValue.merge(cur + "|" + abcClass.name(), value, BigDecimal::add);
            }
            abcRows.add(
                new ReportingInventoryAnalysisModels.InventoryAbcRow(
                    skuCode,
                    skuName,
                    cur,
                    row.get(6, String.class),
                    row.get(7, String.class),
                    row.get(8, String.class),
                    value,
                    share,
                    cumulativeShare,
                    row.get(4, BigDecimal.class),
                    row.get(5, BigDecimal.class),
                    abcClass.name(),
                    abcClass.label(),
                    share != null,
                    share == null ? "这一行的金额算不出来（没有维护成本）⇒ 不可比。" : ""));
          }

          var summary = new ArrayList<ReportingInventoryAnalysisModels.InventoryAbcSummary>();
          for (var abcClass : ReportingInventoryAnalysisModels.AbcClass.values()) {
            for (var cur : denominatorByCurrency.keySet().stream().sorted().toList()) {
              var count = classCount.getOrDefault(cur + "|" + abcClass.name(), 0L);
              var value = classValue.get(cur + "|" + abcClass.name());
              BigDecimal share = null;
              var denominator = denominatorByCurrency.get(cur);
              if (value != null && denominator != null && denominator.signum() != 0) {
                share = value.multiply(new BigDecimal("100")).divide(denominator, RATE_SCALE, RoundingMode.HALF_UP);
              }
              summary.add(
                  new ReportingInventoryAnalysisModels.InventoryAbcSummary(
                      cur, abcClass.name(), abcClass.label(), count, value, share, share != null));
            }
          }

          var total = abcRows.size();
          var from = Math.max(page, 0) * Math.max(size, 1);
          var to = Math.min(from + Math.max(size, 1), total);
          var paged = from >= total ? List.<ReportingInventoryAnalysisModels.InventoryAbcRow>of() : abcRows.subList(from, to);

          warnings.add(
              "ABC 的切分依据是**库存金额的累计占比**（不是销量、也不是 SKU 数）；"
                  + "阈值可改（PUT /reporting/inventory-analysis/rules/ABC_CLASS）。");
          warnings.add("**没有维护成本的 SKU 不进 ABC**（它们的金额算不出来，**不是 0 元**）。");

          return new ReportingInventoryAnalysisModels.InventoryAbcResponse(
              day,
              aCum,
              bCum,
              paged,
              summary,
              total,
              Math.max(page, 0),
              Math.max(size, 1),
              dataUpdatedAt(tenantId),
              DATA_UPDATED_NOTE,
              ABC_CALIBER,
              warnings);
        });
  }

  /**
   * 累计占比 → ABC 类别。
   *
   * @param cumulativePct **累计到本件之后**的金额占比（百分数）
   */
  private static ReportingInventoryAnalysisModels.AbcClass abcClassOf(
      BigDecimal cumulativePct, BigDecimal aCum, BigDecimal bCum) {
    if (cumulativePct.compareTo(aCum) <= 0) return ReportingInventoryAnalysisModels.AbcClass.A;
    if (cumulativePct.compareTo(bCum) <= 0) return ReportingInventoryAnalysisModels.AbcClass.B;
    return ReportingInventoryAnalysisModels.AbcClass.C;
  }

  // ==========================================================================
  // ⑥ 下钻
  // ==========================================================================

  /** SKU 级下钻（真分页 + 真计数）。 */
  public ReportingInventoryAnalysisModels.InventoryDrilldownResponse drilldown(
      UUID tenantId, String skuCode, LocalDate from, LocalDate to, LocalDate asOf, int page, int size) {
    return tenants.run(
        tenantId,
        () -> {
          var warnings = new ArrayList<String>();
          var day = asOf == null ? latestAsOf(tenantId) : asOf;
          if (day == null) {
            return new ReportingInventoryAnalysisModels.InventoryDrilldownResponse(
                null, from, to, List.of(), 0, Math.max(page, 0), Math.max(size, 1), null, DATA_UPDATED_NOTE,
                DRILLDOWN_CALIBER, warnings);
          }
          var where = skuCode == null || skuCode.isBlank() ? "" : " and p.sku_code = ?";
          var args = new ArrayList<Object>();
          args.add(tenantId);
          args.add(day);
          args.add(tenantId);
          args.add(from);
          args.add(to);
          if (skuCode != null && !skuCode.isBlank()) args.add(skuCode.trim());

          var totalRow =
              sql.fetchOne(
                  "select count(*) from reporting_inventory_analysis_position p where p.tenant_id = ? and p.as_of = ?" + where,
                  skuCode == null || skuCode.isBlank()
                      ? new Object[] {tenantId, day}
                      : new Object[] {tenantId, day, skuCode.trim()});
          var total = totalRow == null ? 0L : totalRow.get(0, Long.class);

          var rows =
              sql.fetch(
                  "select p.sku_code, p.sku_name, p.cost_currency, p.category_code, p.warehouse_code,"
                      + " p.warehouse_name, p.shop_code, p.shop_name, p.on_hand, p.reserved, p.available,"
                      + " p.unit_cost, p.inventory_value, p.zero_stock, p.overstock, p.threshold_configured,"
                      + " coalesce(s.qty,0) as sold_qty, s.cogs"
                      + " from reporting_inventory_analysis_position p"
                      + " left join (select sku_code, sum(quantity) as qty, sum(cost_amount) as cogs"
                      + "   from reporting_inventory_analysis_sales_fact"
                      + "   where tenant_id = ? and sold_on >= ? and sold_on <= ? group by 1) s"
                      + "   on s.sku_code = p.sku_code"
                      + " where p.tenant_id = ? and p.as_of = ?" + where
                      + " order by p.sku_code, p.warehouse_code limit ? offset ?",
                  concat(
                      new Object[] {tenantId, from, to, tenantId, day},
                      skuCode == null || skuCode.isBlank() ? new Object[] {} : new Object[] {skuCode.trim()},
                      new Object[] {Math.max(size, 1), Math.max(page, 0) * Math.max(size, 1)}));

          var items = new ArrayList<ReportingInventoryAnalysisModels.InventoryDrilldownRow>();
          for (var row : rows) {
            var value = row.get(12, BigDecimal.class);
            var cogs = row.get(17, BigDecimal.class);
            items.add(
                new ReportingInventoryAnalysisModels.InventoryDrilldownRow(
                    row.get(0, String.class),
                    row.get(1, String.class),
                    row.get(2, String.class),
                    row.get(3, String.class),
                    row.get(4, String.class),
                    row.get(5, String.class),
                    row.get(6, String.class),
                    row.get(7, String.class),
                    row.get(8, BigDecimal.class),
                    row.get(9, BigDecimal.class),
                    row.get(10, BigDecimal.class),
                    row.get(11, BigDecimal.class),
                    value,
                    row.get(16, BigDecimal.class),
                    cogs,
                    row.get(13, Boolean.class) != null && row.get(13, Boolean.class),
                    row.get(14, Boolean.class) != null && row.get(14, Boolean.class),
                    row.get(15, Boolean.class) != null && row.get(15, Boolean.class),
                    "",
                    "",
                    value != null,
                    value == null ? "没有维护成本单价 ⇒ 库存金额算不出来（**不是 0**）。" : ""));
          }
          warnings.add("下钻的每一行都能回到上游核对：数量与金额来自投影快照，销售成本来自同一批销售出库事实。");
          return new ReportingInventoryAnalysisModels.InventoryDrilldownResponse(
              day,
              from,
              to,
              items,
              total,
              Math.max(page, 0),
              Math.max(size, 1),
              dataUpdatedAt(tenantId),
              DATA_UPDATED_NOTE,
              DRILLDOWN_CALIBER,
              warnings);
        });
  }

  // ==========================================================================
  // ⑦ 盘点历史
  // ==========================================================================

  /** 盘点历史。 */
  public ReportingInventoryAnalysisModels.InventoryStocktakeResponse stocktakes(
      UUID tenantId, int page, int size) {
    return tenants.run(
        tenantId,
        () -> {
          var warnings = new ArrayList<String>();
          var totalRow = sql.fetchOne(
              "select count(*) from reporting_inventory_analysis_stocktake_fact where tenant_id = ?", tenantId);
          var total = totalRow == null ? 0L : totalRow.get(0, Long.class);
          var rows =
              sql.fetch(
                  "select stocktake_id, code, scope_type, scope_label, status, frozen_at,"
                      + " line_count, counted_line_count, variance_line_count, book_total"
                      + " from reporting_inventory_analysis_stocktake_fact where tenant_id = ?"
                      + " order by frozen_at desc nulls last, code limit ? offset ?",
                  tenantId,
                  Math.max(size, 1),
                  Math.max(page, 0) * Math.max(size, 1));
          var items = new ArrayList<ReportingInventoryAnalysisModels.InventoryStocktakeRow>();
          var sumLines = 0L;
          var sumCounted = 0L;
          var sumVariance = 0L;
          for (var row : rows) {
            var lineCount = row.get(6, Long.class) == null ? 0L : row.get(6, Long.class);
            var counted = row.get(7, Long.class) == null ? 0L : row.get(7, Long.class);
            var variance = row.get(8, Long.class) == null ? 0L : row.get(8, Long.class);
            sumLines += lineCount;
            sumCounted += counted;
            sumVariance += variance;
            BigDecimal countedRate = null;
            BigDecimal varianceRate = null;
            var comparable = lineCount > 0;
            if (comparable) {
              countedRate =
                  new BigDecimal(counted)
                      .multiply(new BigDecimal("100"))
                      .divide(new BigDecimal(lineCount), RATE_SCALE, RoundingMode.HALF_UP);
              varianceRate =
                  new BigDecimal(variance)
                      .multiply(new BigDecimal("100"))
                      .divide(new BigDecimal(lineCount), RATE_SCALE, RoundingMode.HALF_UP);
            }
            items.add(
                new ReportingInventoryAnalysisModels.InventoryStocktakeRow(
                    row.get(0, UUID.class),
                    row.get(1, String.class),
                    row.get(2, String.class),
                    row.get(3, String.class),
                    row.get(4, String.class),
                    row.get(5, OffsetDateTime.class),
                    lineCount,
                    counted,
                    variance,
                    row.get(9, BigDecimal.class),
                    countedRate,
                    varianceRate,
                    comparable));
          }
          BigDecimal countedRate = null;
          BigDecimal varianceRate = null;
          var summaryComparable = sumLines > 0;
          if (summaryComparable) {
            countedRate =
                new BigDecimal(sumCounted)
                    .multiply(new BigDecimal("100"))
                    .divide(new BigDecimal(sumLines), RATE_SCALE, RoundingMode.HALF_UP);
            varianceRate =
                new BigDecimal(sumVariance)
                    .multiply(new BigDecimal("100"))
                    .divide(new BigDecimal(sumLines), RATE_SCALE, RoundingMode.HALF_UP);
          }
          warnings.add(
              "**账面合计不等于当前库存余额**：它是盘点**冻结那一刻**的账面数（差异要经 warehouse 的调整单审批链才改余额）。");
          warnings.add("盘点差异**不直接改库存余额** —— 那条链归 warehouse 7.4.2.2，本模块只读不改。");
          return new ReportingInventoryAnalysisModels.InventoryStocktakeResponse(
              items,
              new ReportingInventoryAnalysisModels.InventoryStocktakeSummary(
                  total,
                  sumLines,
                  sumCounted,
                  sumVariance,
                  countedRate,
                  varianceRate,
                  summaryComparable),
              total,
              Math.max(page, 0),
              Math.max(size, 1),
              dataUpdatedAt(tenantId),
              DATA_UPDATED_NOTE,
              STOCKTAKE_CALIBER,
              warnings);
        });
  }

  // ==========================================================================
  // 阈值规则
  // ==========================================================================

  /** 阈值规则（**没有配置时给默认**，并在响应里写明是默认值）。 */
  public List<ReportingInventoryAnalysisModels.InventoryAnalysisRule> rules(UUID tenantId) {
    return tenants.run(
        tenantId,
        () -> {
          var out = new ArrayList<ReportingInventoryAnalysisModels.InventoryAnalysisRule>();
          for (var type : List.of("ABC_CLASS", "TURNOVER_TARGET")) {
            var rule = ruleOf(tenantId, type);
            if (rule == null) {
              out.add(
                  "ABC_CLASS".equals(type)
                      ? new ReportingInventoryAnalysisModels.InventoryAnalysisRule(
                          type, DEFAULT_A_CUM_PCT, DEFAULT_B_CUM_PCT, null, 1, true, null)
                      : new ReportingInventoryAnalysisModels.InventoryAnalysisRule(
                          type, null, null, DEFAULT_TARGET_TURNOVER, 1, true, null));
            } else {
              out.add(rule);
            }
          }
          return out;
        });
  }

  /** 改阈值（**只改本模块自己的规则表**，不写任何上游口径）。 */
  public ReportingInventoryAnalysisModels.InventoryAnalysisRule updateRule(
      UUID tenantId,
      String ruleType,
      ReportingInventoryAnalysisModels.InventoryAnalysisRuleInput input) {
    return tenants.run(
        tenantId,
        () -> {
          var existing = ruleOf(tenantId, ruleType);
          var aCum = input.aCumPct() != null ? input.aCumPct() : existing == null ? null : existing.aCumPct();
          var bCum = input.bCumPct() != null ? input.bCumPct() : existing == null ? null : existing.bCumPct();
          var target =
              input.targetTurnover() != null
                  ? input.targetTurnover()
                  : existing == null ? null : existing.targetTurnover();
          var minHistory =
              input.minHistoryDays() != null
                  ? input.minHistoryDays()
                  : existing == null ? 1 : existing.minHistoryDays();
          var enabled = input.enabled() != null ? input.enabled() : existing == null || existing.enabled();
          sql.execute(
              """
              insert into reporting_inventory_analysis_rule
                (id,tenant_id,rule_type,a_cum_pct,b_cum_pct,target_turnover,min_history_days,enabled,updated_at)
              values (?,?,?,?,?,?,?,?,now())
              on conflict (tenant_id,rule_type)
              do update set a_cum_pct = excluded.a_cum_pct,
                            b_cum_pct = excluded.b_cum_pct,
                            target_turnover = excluded.target_turnover,
                            min_history_days = excluded.min_history_days,
                            enabled = excluded.enabled,
                            updated_at = now()
              """,
              Ids.next(),
              tenantId,
              ruleType,
              aCum,
              bCum,
              target,
              minHistory,
              enabled);
          return ruleOf(tenantId, ruleType);
        });
  }

  /** 读一条规则（**没有就是 null**，由调用方给默认值）。 */
  private ReportingInventoryAnalysisModels.InventoryAnalysisRule ruleOf(UUID tenantId, String ruleType) {
    var row =
        sql.fetchOne(
            "select rule_type, a_cum_pct, b_cum_pct, target_turnover, min_history_days, enabled, updated_at"
                + " from reporting_inventory_analysis_rule where tenant_id = ? and rule_type = ?",
            tenantId,
            ruleType);
    if (row == null) return null;
    return new ReportingInventoryAnalysisModels.InventoryAnalysisRule(
        row.get(0, String.class),
        row.get(1, BigDecimal.class),
        row.get(2, BigDecimal.class),
        row.get(3, BigDecimal.class),
        row.get(4, Integer.class) == null ? 1 : row.get(4, Integer.class),
        row.get(5, Boolean.class) != null && row.get(5, Boolean.class),
        row.get(6, OffsetDateTime.class));
  }

  // ==========================================================================
  // 能力声明
  // ==========================================================================

  /** 能力声明：哪些上游真接通、哪些确实算不出来、归谁管。 */
  public ReportingInventoryAnalysisModels.InventoryAnalysisCapabilities capabilities() {
    return new ReportingInventoryAnalysisModels.InventoryAnalysisCapabilities(
        true,
        List.of(
            "库存金额结构", "周转率（成本口径）", "库龄分布", "缺货与超储", "ABC 分类", "呆滞占比", "盘点历史"),
        List.of("仓库", "类目", "店铺", "SKU"),
        List.of(
            "7 库存（warehouse）：库存总览 7.1.1.1 / 库龄与呆滞 7.6.1.1 / 盘点单 7.4.2.1",
            "3 商品（catalog）：SKU 档案 3.1.2.1 / 成本构成 5.2.1.2（**单位成本**）",
            "4 渠道（channel）：平台商品 Listing 4.1.1.1（SKU → 店铺归属）",
            "9 订单（trade）：销售订单列表与明细 9.1.1.1（销量）"),
        List.of(
            "**周转率按仓库拆解不可得**：销售订单不带仓库维度（trade 的发货仓字段恒为 null），"
                + "warehouse 的出库实发量在 8.3.2.1 / 8.3.4.1 交付前拿不到 —— "
                + "把销售成本摊给某个仓库就是编造，所以那一行是「—（不可比）」。",
            "**退货没有从周转率的分子里冲减**：本模块只算销售出库成本，不与 11 售后的退款额对冲。",
            "**库存金额依赖成本单价**：catalog 5.2.1.2 没维护成本的 SKU，其库存金额是「—」，"
                + "**不是 0 元**；这些 SKU 同时不在分子分母里（两侧口径一致）。",
            "**成本单价只含 catalog 自有部分**（采购成本 + 包装成本 + 其它直接成本），"
                + "**不含头程分摊与关税**（那两项在 catalog 的成本明细里，要逐 SKU 另取）。",
            "**库龄与呆滞不含金额**：warehouse 的库存事实层没有成本列 ⇒ 库龄 / 呆滞只报量口径。",
            "**店铺归属只在唯一映射时成立**：同一 SKU 挂在多个店铺的 Listing 上时无法唯一归属，"
                + "本模块如实留空（页面归到「未归属店铺」），**不摊给任何一个店铺**。",
            "**报表是投影**：与业务系统冲突时以业务系统为准；本模块**没有任何「创建业务事实」的权限**。"),
        COST_CALIBER,
        TURNOVER_CALIBER,
        ABC_CALIBER,
        "上游只读、投影可重建；本模块**不产生业务事实、不写上游口径**（库存归 warehouse、成本归 catalog、销量归 trade）。");
  }

  // ==========================================================================
  // 内部
  // ==========================================================================

  /** 拆解维度的 SQL 表达式（**枚举里的常量，不是用户输入**）。 */
  private record DimensionSpec(String name, String key, String label, boolean salesAttributable) {

    /** 页面上的维度名（空值 ⇒ 「未归属」）。 */
    String displayLabel(String key, String label) {
      if ("ALL".equals(name)) return "全部";
      if ("CATEGORY".equals(name)) {
        return key == null || key.isBlank() ? "未归类" : key;
      }
      if ("SHOP".equals(name)) {
        if (key == null || key.isBlank()) return "未归属店铺";
        return label == null || label.isBlank() ? key : label;
      }
      if (key == null || key.isBlank()) return "未归属";
      return label == null || label.isBlank() ? key : label;
    }
  }

  private static DimensionSpec dimensionSpec(String dimension) {
    var value = dimension == null || dimension.isBlank() ? "ALL" : dimension.trim().toUpperCase();
    return switch (value) {
      // ⚠️ **WAREHOUSE 的销售成本不可归属**：销售订单不带仓库维度 ⇒ 那一维度的周转率
      //   **不可比**（如实报 null），而不是把一笔成本摊给某个仓。
      case "WAREHOUSE" -> new DimensionSpec("WAREHOUSE", "warehouse_code", "warehouse_name", false);
      case "CATEGORY" -> new DimensionSpec("CATEGORY", "category_code", "category_code", true);
      case "SHOP" -> new DimensionSpec("SHOP", "shop_code", "shop_name", true);
      case "SKU" -> new DimensionSpec("SKU", "sku_code", "sku_name", true);
      default -> new DimensionSpec("ALL", "''", "''", true);
    };
  }

  /** 最新快照日（{@code asOf <= 到期日} 的那张）。 */
  private LocalDate latestAsOf(UUID tenantId) {
    return latestAsOf(tenantId, LocalDate.now(ZoneOffset.UTC).plusDays(1));
  }

  /** 最新快照日（{@code asOf <= 到期日} 的那张）。 */
  private LocalDate latestAsOf(UUID tenantId, LocalDate notAfter) {
    var row =
        sql.fetchOne(
            "select max(as_of) from reporting_inventory_analysis_position where tenant_id = ? and as_of <= ?",
            tenantId,
            notAfter);
    return row == null ? null : row.get(0, LocalDate.class);
  }

  /** 最新库龄事实日。 */
  private LocalDate latestAgingAsOf(UUID tenantId) {
    var row =
        sql.fetchOne(
            "select max(as_of) from reporting_inventory_analysis_aging_fact where tenant_id = ?", tenantId);
    return row == null ? null : row.get(0, LocalDate.class);
  }

  /** 某个快照日、按维度聚合的库存金额。 */
  private Map<String, BigDecimal> valuesAt(
      UUID tenantId, LocalDate day, DimensionSpec spec, String currency) {
    var map = new HashMap<String, BigDecimal>();
    if (day == null) return map;
    var rows =
        sql.fetch(
            "select " + spec.key() + " as dim_key, " + spec.label() + " as dim_label, cost_currency,"
                + " sum(inventory_value) as inv_value"
                + " from reporting_inventory_analysis_position"
                + " where tenant_id = ? and as_of = ?"
                + (currency == null ? "" : " and cost_currency = ?")
                + " group by 1,2,3",
            currency == null
                ? new Object[] {tenantId, day}
                : new Object[] {tenantId, day, currency});
    for (var row : rows) {
      var value = row.get(3, BigDecimal.class);
      if (value == null) continue;
      map.put(row.get(0, String.class) + "|" + row.get(1, String.class) + "|" + row.get(2, String.class), value);
    }
    return map;
  }

  /** 窗口内的销售出库成本（**按维度聚合**；缺成本的行不计入 ⇒ 分子分母口径一致）。 */
  private Map<String, BigDecimal> costOfGoodsSold(
      UUID tenantId, LocalDate from, LocalDate to, DimensionSpec spec, String currency) {
    var map = new HashMap<String, BigDecimal>();
    var rows =
        sql.fetch(
            "select " + spec.key() + " as dim_key, " + spec.label() + " as dim_label, cost_currency,"
                + " sum(cost_amount) as cogs"
                + " from reporting_inventory_analysis_sales_fact"
                + " where tenant_id = ? and sold_on >= ? and sold_on <= ? and cost_amount is not null"
                + (currency == null ? "" : " and cost_currency = ?")
                + " group by 1,2,3",
            currency == null
                ? new Object[] {tenantId, from, to}
                : new Object[] {tenantId, from, to, currency});
    for (var row : rows) {
      var value = row.get(3, BigDecimal.class);
      if (value == null) continue;
      map.put(row.get(0, String.class) + "|" + row.get(1, String.class) + "|" + row.get(2, String.class), value);
    }
    return map;
  }

  /**
   * 没有维护成本的 SKU 数（**按维度分组**）。
   *
   * <p>这些 SKU 的库存与销量**两侧都不在**分子分母里 —— 两侧口径一致 ⇒ 周转率本身仍可比，
   * 这一列只是让人知道这份周转率覆盖了多大范围。
   */
  private Map<String, Long> missingSkuCounts(
      UUID tenantId, LocalDate day, DimensionSpec spec, String currency) {
    var map = new HashMap<String, Long>();
    if (day == null) return map;
    var rows =
        sql.fetch(
            "select " + spec.key() + " as dim_key, " + spec.label() + " as dim_label, cost_currency,"
                + " count(distinct sku_code) as cnt"
                + " from reporting_inventory_analysis_position"
                + " where tenant_id = ? and as_of = ? and inventory_value is null"
                + (currency == null ? "" : " and cost_currency = ?")
                + " group by 1,2,3",
            currency == null
                ? new Object[] {tenantId, day}
                : new Object[] {tenantId, day, currency});
    for (var row : rows) {
      var count = row.get(3, Long.class);
      map.put(
          row.get(0, String.class) + "|" + row.get(1, String.class) + "|" + row.get(2, String.class),
          count == null ? 0L : count);
    }
    return map;
  }

  /** 全历史快照（按 as_of × 维度聚合），供趋势用。 */
  private Map<LocalDate, Map<String, BigDecimal>> valueSeries(
      UUID tenantId, DimensionSpec spec, String currency) {
    var series = new LinkedHashMap<LocalDate, Map<String, BigDecimal>>();
    var rows =
        sql.fetch(
            "select as_of, " + spec.key() + " as dim_key, " + spec.label() + " as dim_label, cost_currency,"
                + " sum(inventory_value) as inv_value"
                + " from reporting_inventory_analysis_position where tenant_id = ?"
                + (currency == null ? "" : " and cost_currency = ?")
                + " group by 1,2,3,4 order by 1",
            currency == null
                ? new Object[] {tenantId}
                : new Object[] {tenantId, currency});
    for (var row : rows) {
      var day = row.get(0, LocalDate.class);
      if (day == null) continue;
      var value = row.get(4, BigDecimal.class);
      if (value == null) continue;
      var composite = row.get(1, String.class) + "|" + row.get(2, String.class) + "|" + row.get(3, String.class);
      series.computeIfAbsent(day, k -> new HashMap<>()).put(composite, value);
    }
    return series;
  }

  /** 某一天的取值（**不晚于该日**的最近一张快照）。 */
  private Map<String, BigDecimal> valueAt(
      Map<LocalDate, Map<String, BigDecimal>> series, LocalDate notAfter) {
    Map<String, BigDecimal> picked = null;
    for (var entry : series.entrySet()) {
      if (entry.getKey().isAfter(notAfter)) break;
      picked = entry.getValue();
    }
    return picked == null ? Map.of() : picked;
  }

  /** 维度键的并集（三张聚合表的键合并，保证「有头寸没销量」的行也在）。 */
  private List<String> dimensionKeys(
      DimensionSpec spec,
      Map<String, BigDecimal> opening,
      Map<String, BigDecimal> closing,
      Map<String, BigDecimal> cogs) {
    var keys = new java.util.TreeSet<String>();
    keys.addAll(opening.keySet());
    keys.addAll(closing.keySet());
    keys.addAll(cogs.keySet());
    return new ArrayList<>(keys);
  }

  /** 窗口按粒度切成若干段。 */
  private static List<Period> periods(LocalDate from, LocalDate to, String granularity) {
    var out = new ArrayList<Period>();
    var cursor = from;
    while (!cursor.isAfter(to)) {
      LocalDate end;
      switch (granularity) {
        case "WEEK" -> end = cursor.plusDays(6);
        case "MONTH" -> end = cursor.withDayOfMonth(cursor.lengthOfMonth());
        default -> end = cursor;
      }
      if (end.isAfter(to)) end = to;
      out.add(new Period(cursor, end));
      cursor = end.plusDays(1);
    }
    return out;
  }

  /** 一段期间。 */
  private record Period(LocalDate start, LocalDate end) {}

  /** 数据更新时间（**没有投影过时是 null** —— 「尚未投影任何事实」）。 */
  public OffsetDateTime dataUpdatedAt(UUID tenantId) {
    OffsetDateTime latest = null;
    for (var table :
        List.of(
            "reporting_inventory_analysis_position",
            "reporting_inventory_analysis_sales_fact",
            "reporting_inventory_analysis_aging_fact",
            "reporting_inventory_analysis_stocktake_fact")) {
      var row = sql.fetchOne("select max(updated_at) from " + table + " where tenant_id = ?", tenantId);
      if (row == null) continue;
      var at = row.get(0, OffsetDateTime.class);
      if (at == null) continue;
      if (latest == null || at.isAfter(latest)) latest = at;
    }
    return latest;
  }

  /** 拼接参数（jOOQ 的 {@code sql.fetch(sql, Object...)} 不接受 List）。 */
  private static Object[] concat(Object[] head, Object[] middle, Object[] tail) {
    var out = new Object[head.length + middle.length + tail.length];
    System.arraycopy(head, 0, out, 0, head.length);
    System.arraycopy(middle, 0, out, head.length, middle.length);
    System.arraycopy(tail, 0, out, head.length + middle.length, tail.length);
    return out;
  }

  /** 拼接参数（两段）。 */
  private static Object[] concat(Object[] head, Object[] tail) {
    return concat(head, new Object[] {}, tail);
  }

  // ==========================================================================
  // 口径说明（**页面上原样显示**）
  // ==========================================================================

  static final String COST_CALIBER =
      "库存金额 = 在手数量 × **catalog 5.2.1.2 成本构成里的单位成本**"
          + "（采购成本 + 包装成本 + 其它直接成本；**不含头程分摊与关税**）。"
          + "**多币种绝不相加** —— 每一行带自己的币种。";

  static final String TURNOVER_CALIBER =
      "周转率 = **销售出库成本** ÷ **平均库存金额**（(期初 + 期末) ÷ 2，两者都是成本口径）。"
          + "🔴 **没有用售价**：用售价算会虚高（蓝图加粗句）。"
          + "分母为 0 ⇒ 周转率**不可比**（不是 0）；没有期初快照时退回期末口径并写明。"
          + "**先汇总再相除**：分子分母都是先加总再相除，不是对逐 SKU 的周转率求平均。";

  static final String ABC_CALIBER =
      "ABC 分类按**库存金额的累计占比**切分（不是销量、也不是 SKU 数）："
          + "把 SKU 按金额从大到小排，**累计到本件之后**的占比 ≤ aCumPct 归 A 类、≤ bCumPct 归 B 类，"
          + "其余归 C 类（跨过阈值的那一件落在它跨入的那一类，不降级）。"
          + "阈值可按租户改（PUT /reporting/inventory-analysis/rules/ABC_CLASS）。"
          + "**没有维护成本的 SKU 不进 ABC**（它们的金额算不出来，不是 0 元）。";

  static final String AGING_CALIBER =
      "库龄与呆滞的**判定归 warehouse 7.6.1.1**（含滞销线 / 呆滞线阈值策略），本模块只搬运。"
          + "库龄分桶与呆滞占比都是**量口径**（warehouse 的库存事实层没有成本列）。";

  static final String STOCK_STATUS_CALIBER =
      "缺货 = 可用量 ≤ 0；超储 = 在手量超过**已配置**的阈值。"
          + "**未设阈值**的行单独计数 —— 「没查过」与「查过没超」是两件事。";

  static final String DRILLDOWN_CALIBER =
      "下钻逐行给出：数量、**成本口径**的单价与库存金额、窗口内的销量与销售出库成本、"
          + "缺货 / 超储标记。每一行都能回到上游核对。";

  static final String STOCKTAKE_CALIBER =
      "盘点历史：账面行数 / 已录实盘行数 / 有差异行数三列计数都来自 warehouse 7.4.2.1。"
          + "**账面合计不等于当前库存余额**（那是冻结那一刻的账面数）。";

  static final String DATA_UPDATED_NOTE =
      "数据截至（这是**投影**的更新时间，不是业务系统的实时时刻）；库存头寸按天拍快照，"
          + "看到的是快照日的数据。";
}
