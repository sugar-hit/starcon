package com.starcon.reporting;

import com.starcon.tenancy.TenantTransactions;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Service;

/**
 * 13.2.1.1 销量趋势的**读模型**（蓝图「输入」句：时间范围；维度与对比方式；异常事件标注）。
 *
 * <p>⚠️ **本类不重算口径**：口径（单位 / 聚合方式 / 被比较指标 / 维度）全部来自
 * {@code reporting_metric_definition}（13.1.1.1 的公司维度口径，**本模块引用不重定义**）
 * 与 {@code reporting_sales_metric_definition}（本模块新增的维度口径与对比口径），
 * 本类只**按定义聚合**。
 *
 * <p>⚠️ **「算不出来」与「等于 0」必须分开**：基期缺失、基期为 0、事实不存在，
 * 三种情况一律返回 {@code null} / {@code NO_DATA} / {@code NOT_COMPARABLE}，**不用 0 冒充**。
 *
 * <p>⚠️ **不跨币种相加**：每个币种各自成一条序列 / 一行，**绝不**把不同币种加在一起。
 * 给了本位币且有生效汇率时额外给出折算值，并把**汇率与时点**一起返回。
 *
 * <p>⚠️ **分桶在查询期做**：事实表存的是**精确时刻**，分桶用
 * {@code date_trunc(bucket_start AT TIME ZONE tz)} ⇒ 半小时偏移的时区也精确，
 * 换时区不必重新投影。
 */
@Service
public class ReportingSalesTrendService {

  /** 异常判定阈值：偏离基线（中位数）≥ 50% 判成异常。 */
  static final BigDecimal ANOMALY_THRESHOLD = new BigDecimal("0.50");

  /** 查询期取数的**余量天数**（要覆盖同比基期与上一个桶）。 */
  private static final int RANGE_MARGIN_DAYS = 40;

  private final DSLContext sql;
  private final TenantTransactions tenants;
  private final ReportingSalesTrendRefreshService refresh;

  public ReportingSalesTrendService(
      DSLContext sql, TenantTransactions tenants, ReportingSalesTrendRefreshService refresh) {
    this.sql = sql;
    this.tenants = tenants;
    this.refresh = refresh;
  }

  // ==========================================================================
  // 口径目录
  // ==========================================================================

  /**
   * 读该租户的**销量趋势指标口径目录**（口径唯一的展示形态）。
   *
   * <p>⚠️ **公司维度的三个基础口径来自 13.1.1.1 的口径表**（本模块**引用**它们，
   * 不另起一份定义）—— 目录里只列**本模块真正用到的**指标。
   */
  public List<ReportingSalesTrendModels.SalesTrendMetricDefinitionView> metricDefinitions(
      UUID tenantId) {
    return tenants.run(
        tenantId,
        () -> {
          refresh.seedDefinitions(tenantId);
          var definitions = definitionMap(tenantId);
          var out = new ArrayList<ReportingSalesTrendModels.SalesTrendMetricDefinitionView>();
          for (var definition : definitions.values()) {
            out.add(
                new ReportingSalesTrendModels.SalesTrendMetricDefinitionView(
                    definition.metricCode(),
                    definition.displayName(),
                    definition.unit(),
                    definition.aggregation(),
                    definition.baseMetricCode(),
                    definition.dimensionType(),
                    definition.currencyAware(),
                    definition.description(),
                    definition.ownerModule()));
          }
          return out;
        });
  }

  /** 一个口径定义（跨两张表合并后的形态）。 */
  private record Definition(
      String metricCode,
      String displayName,
      String unit,
      String aggregation,
      String baseMetricCode,
      String dimensionType,
      boolean currencyAware,
      String description,
      String ownerModule) {}

  /**
   * 口径定义表（**两张表合并**）。
   *
   * <p>⚠️ 公司维度的三个基础口径在 {@code reporting_metric_definition}
   * （13.1.1.1 登记，内容唯一来源 {@link ReportingMetricCatalog#STANDARD}），
   * 其余在 {@code reporting_sales_metric_definition}。**本模块不重定义公司维度口径。**
   */
  private Map<String, Definition> definitionMap(UUID tenantId) {
    var out = new LinkedHashMap<String, Definition>();
    var companyCodes = ReportingSalesTrendCatalog.baseMetricCodes(
        ReportingSalesTrendCatalog.DimensionType.TOTAL);
    var shared =
        sql.fetch(
            """
            select metric_code, display_name, unit, aggregation, numerator_metric_code,
                   denominator_metric_code, currency_aware, description, owner_module
              from reporting_metric_definition
             where tenant_id = ? and enabled
             order by metric_code
            """,
            tenantId);
    for (var row : shared) {
      var code = row.get(0, String.class);
      if (!companyCodes.contains(code)) continue;
      out.put(
          code,
          new Definition(
              code,
              row.get(1, String.class),
              row.get(2, String.class),
              row.get(3, String.class),
              null,
              "TOTAL",
              Boolean.TRUE.equals(row.get(6, Boolean.class)),
              row.get(7, String.class),
              row.get(8, String.class)));
    }
    var own =
        sql.fetch(
            """
            select metric_code, display_name, unit, aggregation, base_metric_code,
                   dimension_type, currency_aware, description, owner_module
              from reporting_sales_metric_definition
             where tenant_id = ? and enabled
             order by metric_code
            """,
            tenantId);
    for (var row : own) {
      var code = row.get(0, String.class);
      out.put(
          code,
          new Definition(
              code,
              row.get(1, String.class),
              row.get(2, String.class),
              row.get(3, String.class),
              row.get(4, String.class),
              row.get(5, String.class),
              Boolean.TRUE.equals(row.get(6, Boolean.class)),
              row.get(7, String.class),
              row.get(8, String.class)));
    }
    return out;
  }

  // ==========================================================================
  // 趋势
  // ==========================================================================

  /**
   * 销量趋势：趋势点列 + 环比 / 同比 + 异常标注。
   *
   * @param metricCodes 要看的指标（可空 ⇒ 该维度下的全部基础口径）
   */
  public ReportingSalesTrendModels.SalesTrendResponse trend(
      UUID tenantId,
      LocalDate requestedFrom,
      LocalDate requestedTo,
      ReportingSalesTrendGranularity granularity,
      ZoneId zone,
      List<String> metricCodes,
      String baseCurrency) {
    return tenants.run(
        tenantId,
        () -> {
          refresh.seedDefinitions(tenantId);
          var definitions = definitionMap(tenantId);
          var from = granularity.alignStart(requestedFrom);
          var to = granularity.alignEnd(requestedTo);
          var wanted = selectMetrics(definitions, "TOTAL", metricCodes);
          var series = loadSeries(tenantId, "TOTAL", wanted, definitions, from, to, granularity, zone);

          var events = loadEvents(tenantId, from, to, zone, "", "");
          var rates =
              baseCurrency == null
                  ? List.<ReportingSalesTrendModels.SalesTrendCurrencyRate>of()
                  : rates(tenantId, baseCurrency, to, seriesCurrencies(series));

          var out = new ArrayList<ReportingSalesTrendModels.SalesTrendSeries>();
          var anomalies = new ArrayList<ReportingSalesTrendModels.SalesTrendAnomaly>();
          for (var entry : series.entrySet()) {
            var metric = entry.getKey().metricCode();
            var currency = entry.getKey().currency();
            var definition = definitions.get(metric);
            var buckets = entry.getValue();
            var baseline = median(buckets, windowKeys(from, to, granularity));
            var points = new ArrayList<ReportingSalesTrendModels.SalesTrendPoint>();
            for (var key : windowKeys(from, to, granularity)) {
              var value = buckets.get(key);
              // ⚠️ **没有事实的那天没有点**（与 13.1.1.1 同一纪律）：不是 0，也不是一个空点。
              //   「没有事实」与「卖了 0」在页面上长得一样，含义却相反。
              if (value == null) continue;
              var previousKey = granularity.previous(key);
              var yearAgoKey = granularity.yearAgo(key);
              var previousValue = buckets.get(previousKey);
              var yearAgoValue = buckets.get(yearAgoKey);
              var bucketStart = key.toLocalDate();
              var bucketEnd = granularity.bucketEndDate(key);
              var hour =
                  granularity == ReportingSalesTrendGranularity.HOUR ? key.getHour() : null;
              var label = granularity.bucketLabel(bucketStart, bucketEnd, hour);
              var bucketEvents = matchingEvents(events, bucketStart, bucketEnd, "", "");
              var deviation = deviation(value, baseline);
              var anomaly = deviation != null && deviation.abs().compareTo(ANOMALY_THRESHOLD) >= 0;
              var note =
                  anomaly
                      ? "偏离中位数 "
                          + deviation.multiply(new BigDecimal("100")).setScale(2, RoundingMode.HALF_UP)
                          + "%（阈值 ±"
                          + ANOMALY_THRESHOLD.multiply(new BigDecimal("100")).setScale(0, RoundingMode.HALF_UP)
                          + "%）"
                      : "";
              var rate = rates.isEmpty() ? null : rateFor(rates, currency);
              points.add(
                  new ReportingSalesTrendModels.SalesTrendPoint(
                      bucketStart,
                      bucketEnd,
                      hour,
                      label,
                      currency,
                      value,
                      value == null || rate == null ? null : value.multiply(rate.rate()),
                      rate == null ? null : rate.baseCurrency(),
                      previousValue,
                      growth(value, previousValue),
                      previousKey.toLocalDate(),
                      yearAgoValue,
                      growth(value, yearAgoValue),
                      yearAgoKey.toLocalDate(),
                      anomaly,
                      note,
                      bucketEvents));
              if (anomaly) {
                anomalies.add(
                    new ReportingSalesTrendModels.SalesTrendAnomaly(
                        bucketStart,
                        bucketEnd,
                        hour,
                        metric,
                        currency,
                        value,
                        baseline,
                        deviation.multiply(new BigDecimal("100")).setScale(2, RoundingMode.HALF_UP),
                        deviation.signum() > 0 ? "SPIKE" : "DIP",
                        note
                            + (bucketEvents.isEmpty()
                                ? " ⚠️ **没有关联到事件**（查不到原因）"
                                : "；已关联 " + bucketEvents.size() + " 个事件"),
                        bucketEvents));
              }
            }
            out.add(
                new ReportingSalesTrendModels.SalesTrendSeries(
                    metric,
                    definition == null ? metric : definition.displayName(),
                    definition == null ? "" : definition.unit(),
                    "TOTAL",
                    "",
                    "全部店铺",
                    currency,
                    definition == null || definition.currencyAware(),
                    points));
          }

          var dataUpdatedAt = dataUpdatedAt(tenantId);
          var hasFacts = !series.isEmpty();
          var anyFacts = hasFacts || hasAnyFact(tenantId);
          var status = !anyFacts ? "NOT_PROJECTED" : "PROJECTED";
          var emptyReason = anyFacts ? "NO_FACTS_IN_WINDOW" : "NOT_PROJECTED";
          var emptyNote =
              anyFacts
                  ? "这个窗口里没有销量事实（该租户**有**投影，只是这个窗口没有数据）—— "
                      + "换时间范围或先刷新投影。这是「这个窗口没有」，不是「读不到」。"
                  : "这个租户还没有投影过销量事实 —— 先刷新投影（POST /reporting/sales-trend/refresh）。"
                      + "这是「还没有数据」，不是「读不到」。";
          var warnings = new ArrayList<String>();
          warnings.add(
              "金额按币种分别列出，**不跨币种相加**；趋势点的「环比」比的是**上一个同粒度桶**，"
                  + "「同比」比的是**去年同期**。基期缺失或为 0 时显示「不可比」，**不是 0%**。");
          if (baseCurrency != null) {
            warnings.add(
                rates.isEmpty()
                    ? "⚠️ 给了本位币但**没有生效的汇率** ⇒ 不做折算（**绝不按 1:1 冒充**）。"
                    : "本位币折算用的是**各币种最近一条生效汇率**（时点见 baseRates）—— "
                        + "今天的汇率变了不会让上月的报表变。");
          }
          warnings.add(
              "公司维度（全部店铺）的口径与店铺 / 商品维度**不可相加**："
                  + "商品维度用的是订单明细行金额（订单级折扣 / 运费不摊到行），"
                  + "店铺维度不含挂不上店铺的订单。");
          return new ReportingSalesTrendModels.SalesTrendResponse(
              requestedFrom,
              requestedTo,
              from,
              to,
              granularity.name(),
              zone.getId(),
              out,
              anomalies,
              events,
              definitionViews(definitions, wanted),
              dataUpdatedAt,
              "最近一次投影落库时刻（报表不是实时的）。",
              status,
              status,
              emptyReason,
              emptyNote,
              warnings,
              caliberNotes(definitions),
              baseCurrency,
              rates);
        });
  }

  // ==========================================================================
  // 多维对比
  // ==========================================================================

  /**
   * 多维对比：同比 / 环比（{@code dimension=TOTAL}）、店铺间（{@code STORE}）、
   * 产品间（{@code PRODUCT}）。
   */
  public ReportingSalesTrendModels.SalesTrendComparisonResponse comparison(
      UUID tenantId,
      LocalDate requestedFrom,
      LocalDate requestedTo,
      ReportingSalesTrendGranularity granularity,
      ZoneId zone,
      String dimension,
      List<String> metricCodes,
      String baseCurrency) {
    return tenants.run(
        tenantId,
        () -> {
          refresh.seedDefinitions(tenantId);
          var definitions = definitionMap(tenantId);
          var dimensionType = dimension == null ? "TOTAL" : dimension.trim().toUpperCase();
          if (!List.of("TOTAL", "STORE", "PRODUCT").contains(dimensionType)) {
            throw new IllegalArgumentException("维度只能是 TOTAL / STORE / PRODUCT：" + dimension);
          }
          var from = granularity.alignStart(requestedFrom);
          var to = granularity.alignEnd(requestedTo);
          var windows =
              ReportingSalesTrendRefreshService.windows(requestedFrom, requestedTo, granularity);
          var previous = windows.get(1);
          var yearAgo = windows.get(2);

          var wanted = selectMetrics(definitions, dimensionType, metricCodes);
          var current =
              loadSeries(tenantId, dimensionType, wanted, definitions, from, to, granularity, zone);
          var previousSeries =
              loadSeries(
                  tenantId,
                  dimensionType,
                  wanted,
                  definitions,
                  previous.from(),
                  previous.to(),
                  granularity,
                  zone);
          var yearAgoSeries =
              loadSeries(
                  tenantId,
                  dimensionType,
                  wanted,
                  definitions,
                  yearAgo.from(),
                  yearAgo.to(),
                  granularity,
                  zone);

          var currencies = new LinkedHashSet<String>();
          for (var entry : current.entrySet()) currencies.add(entry.getKey().currency());
          var rates =
              baseCurrency == null
                  ? List.<ReportingSalesTrendModels.SalesTrendCurrencyRate>of()
                  : rates(tenantId, baseCurrency, to, currencies);

          // ---- 行 = 一个维度键 × 一个币种 ----
          var rowKeys = new LinkedHashMap<String, LinkedHashSet<String>>();
          for (var entry : current.entrySet()) {
            var key = entry.getKey().dimensionKey();
            rowKeys.computeIfAbsent(key, k -> new LinkedHashSet<>()).add(entry.getKey().currency());
          }
          var rows = new ArrayList<ReportingSalesTrendModels.SalesTrendComparisonRow>();
          for (var entry : rowKeys.entrySet()) {
            var dimensionKey = entry.getKey();
            for (var currency : entry.getValue()) {
              var metrics = new ArrayList<ReportingSalesTrendModels.SalesTrendComparisonMetric>();
              for (var metric : wanted) {
                var definition = definitions.get(metric);
                var value = sum(current, metric, dimensionKey, currency, from, to, granularity);
                var previousValue =
                    sum(previousSeries, metric, dimensionKey, currency, previous.from(), previous.to(), granularity);
                var yearAgoValue =
                    sum(yearAgoSeries, metric, dimensionKey, currency, yearAgo.from(), yearAgo.to(), granularity);
                var availability = value == null ? "NO_DATA" : "AVAILABLE";
                var note =
                    value == null
                        ? "该维度键在这个窗口、这个币种下没有这个指标的事实数据。"
                        : "";
                var rate = rateFor(rates, currency);
                metrics.add(
                    new ReportingSalesTrendModels.SalesTrendComparisonMetric(
                        metric,
                        definition == null ? metric : definition.displayName(),
                        definition == null ? "" : definition.unit(),
                        value,
                        value == null || rate == null ? null : value.multiply(rate.rate()),
                        previousValue,
                        growth(value, previousValue),
                        yearAgoValue,
                        growth(value, yearAgoValue),
                        availability,
                        note));
              }
              rows.add(
                  new ReportingSalesTrendModels.SalesTrendComparisonRow(
                      dimensionType,
                      dimensionKey,
                      dimensionLabel(dimensionType, dimensionKey),
                      currency,
                      metrics));
            }
          }
          // 按成交金额倒序（**币种内**排序：跨币种的大小本来就不可比）。
          rows.sort(
              Comparator.comparing(
                      (ReportingSalesTrendModels.SalesTrendComparisonRow row) ->
                          row.metrics().stream()
                              .filter(m -> m.metricCode().equals(ReportingMetricCatalog.SALES_AMOUNT)
                                  || m.metricCode().equals(ReportingSalesTrendCatalog.STORE_SALES_AMOUNT)
                                  || m.metricCode().equals(ReportingSalesTrendCatalog.PRODUCT_SALES_AMOUNT))
                              .map(ReportingSalesTrendModels.SalesTrendComparisonMetric::value)
                              .filter(java.util.Objects::nonNull)
                              .findFirst()
                              .orElse(BigDecimal.ZERO))
                  .reversed()
                  .thenComparing(ReportingSalesTrendModels.SalesTrendComparisonRow::dimensionKey));

          var dataUpdatedAt = dataUpdatedAt(tenantId);
          var hasFacts = !rows.isEmpty();
          var emptyReason = hasFacts ? null : "NOT_PROJECTED";
          var emptyNote =
              hasFacts
                  ? ""
                  : "这个租户还没有投影过销量事实 —— 先刷新投影。这是「还没有数据」，不是「读不到」。";
          var warnings = new ArrayList<String>();
          warnings.add(
              "对比矩阵里的「环比」比的是**上一等长窗口**，「同比」比的是**去年同期窗口**；"
                  + "基期缺失或为 0 时显示「不可比」，**不是 0%**。");
          warnings.add(
              "金额按币种分别列出，**不跨币种相加**；排序也**在币种内**做 —— "
                  + "一个 USD 店铺和一个 EUR 店铺的大小本来就不可比。");
          if (baseCurrency != null) {
            warnings.add(
                rates.isEmpty()
                    ? "⚠️ 给了本位币但**没有生效的汇率** ⇒ 不做折算（**绝不按 1:1 冒充**）。"
                    : "本位币折算用的是**各币种最近一条生效汇率**（时点见 baseRates）。");
          }
          return new ReportingSalesTrendModels.SalesTrendComparisonResponse(
              requestedFrom,
              requestedTo,
              from,
              to,
              granularity.name(),
              zone.getId(),
              dimensionType,
              rows,
              definitionViews(definitions, wanted),
              dataUpdatedAt,
              "最近一次投影落库时刻（报表不是实时的）。",
              hasFacts ? "PROJECTED" : "NOT_PROJECTED",
              hasFacts ? "" : emptyNote,
              emptyReason,
              emptyNote,
              warnings,
              caliberNotes(definitions),
              baseCurrency,
              rates);
        });
  }

  // ==========================================================================
  // 异常事件
  // ==========================================================================

  /** 异常事件清单（大促 / 秒杀 / 缺货 / 断货）。 */
  public ReportingSalesTrendModels.SalesTrendEventResponse events(
      UUID tenantId, LocalDate from, LocalDate to, ZoneId zone, String shopCode, String skuCode) {
    return tenants.run(
        tenantId,
        () -> {
          refresh.seedDefinitions(tenantId);
          var all = loadEvents(tenantId, from, to, zone, shopCode, skuCode);
          var dataUpdatedAt = dataUpdatedAt(tenantId);
          var emptyReason = all.isEmpty() ? "NO_EVENTS" : null;
          var emptyNote =
              all.isEmpty()
                  ? "这个窗口里没有异常事件标注（大促 / 秒杀 / 缺货 / 断货）。"
                      + "⚠️ 断货与缺货事件来自上游的**断货风险快照** —— 上游还没算过快照时这里就是空的"
                      + "（是「上游没有」，不是「读不到」）。"
                  : "";
          return new ReportingSalesTrendModels.SalesTrendEventResponse(
              from,
              to,
              all,
              dataUpdatedAt,
              "最近一次投影落库时刻（报表不是实时的）。",
              all.isEmpty() ? "NOT_PROJECTED" : "PROJECTED",
              emptyNote,
              emptyReason,
              emptyNote,
              List.of(
                  "事件覆盖的日期按**本次请求的时区**换算（大促 / 秒杀有精确起止时刻，"
                      + "断货 / 缺货是纯日期事件）。"));
        });
  }

  // ==========================================================================
  // 取数
  // ==========================================================================

  /** 一个序列的键：指标 + 币种 + 维度键。 */
  private record SeriesKey(String metricCode, String currency, String dimensionKey) {}

  /**
   * 取一个维度下的事实，并**按粒度分桶聚合**。
   *
   * <p>⚠️ **分桶在 SQL 里做**（{@code date_trunc(bucket_start AT TIME ZONE tz)}）：
   * 半小时偏移的时区也精确，换时区不必重新投影。
   *
   * <p>⚠️ **取数范围留了余量**：同比基期（去年同期）与上一个桶都在范围里，
   * 否则环比 / 同比永远算不出来。
   */
  private Map<SeriesKey, Map<LocalDateTime, BigDecimal>> loadSeries(
      UUID tenantId,
      String dimensionType,
      List<String> metricCodes,
      Map<String, Definition> definitions,
      LocalDate from,
      LocalDate to,
      ReportingSalesTrendGranularity granularity,
      ZoneId zone) {
    var out = new LinkedHashMap<SeriesKey, Map<LocalDateTime, BigDecimal>>();
    if (metricCodes.isEmpty()) return out;
    var rangeStart =
        from.minusYears(1).minusDays(RANGE_MARGIN_DAYS).atStartOfDay(zone).toOffsetDateTime();
    var rangeEnd =
        to.plusDays(RANGE_MARGIN_DAYS).plusDays(1).atStartOfDay(zone).toOffsetDateTime();
    // ⚠️ **IN 列表按个数拼占位符**（jOOQ 的裸 SQL 不接受数组参数）。
    var placeholders = new StringBuilder();
    for (var i = 0; i < metricCodes.size(); i++) {
      placeholders.append(i == 0 ? "?" : ",?");
    }
    var params = new ArrayList<Object>();
    params.add(granularity.sqlUnit());
    params.add(zone.getId());
    params.add(tenantId);
    params.add(dimensionType);
    params.add(rangeStart);
    params.add(rangeEnd);
    params.addAll(metricCodes);
    var rows =
        sql.fetch(
            """
            select date_trunc(?, f.bucket_start at time zone ?) as bucket,
                   f.metric_code, f.currency, f.dimension_key,
                   sum(f.value) as value_sum, sum(f.quantity) as quantity_sum
              from reporting_sales_trend_fact f
             where f.tenant_id = ? and f.dimension_type = ?
               and f.bucket_start >= ?::timestamptz and f.bucket_start < ?::timestamptz
               and f.metric_code in ("""
                + placeholders
                + """
              )
             group by 1,2,3,4
             order by 1,2,3,4
            """,
            params.toArray());
    for (var row : rows) {
      var bucket = row.get(0, LocalDateTime.class);
      if (bucket == null) continue;
      var metricCode = row.get(1, String.class);
      var currency = row.get(2, String.class);
      var dimensionKey = row.get(3, String.class) == null ? "" : row.get(3, String.class);
      var value = row.get(4, BigDecimal.class);
      var quantity = row.get(5, BigDecimal.class);
      var key = new SeriesKey(metricCode, currency, dimensionKey);
      var buckets = out.computeIfAbsent(key, k -> new TreeMap<>());
      // ⚠️ **数量型指标读 quantity 列，其余读 value 列**（由口径表的 unit 决定，不猜）。
      var definition = definitions.get(metricCode);
      var isQuantity =
          definition != null && "QUANTITY".equals(definition.unit());
      var measure = isQuantity ? quantity : value;
      buckets.merge(bucket, measure == null ? BigDecimal.ZERO : measure, BigDecimal::add);
    }
    return out;
  }

  /** 窗口内的桶键序列。 */
  private List<LocalDateTime> windowKeys(
      LocalDate from, LocalDate to, ReportingSalesTrendGranularity granularity) {
    var out = new ArrayList<LocalDateTime>();
    var key = granularity.truncate(from.atStartOfDay());
    var last = granularity.truncate(to.atStartOfDay());
    while (!key.isAfter(last)) {
      out.add(key);
      key = granularity.shift(key, 1);
    }
    return out;
  }

  /** 窗口内的合计（按桶求和）。 */
  private BigDecimal sum(
      Map<SeriesKey, Map<LocalDateTime, BigDecimal>> series,
      String metricCode,
      String dimensionKey,
      String currency,
      LocalDate from,
      LocalDate to,
      ReportingSalesTrendGranularity granularity) {
    var buckets = series.get(new SeriesKey(metricCode, currency, dimensionKey));
    if (buckets == null) return null;
    var total = BigDecimal.ZERO;
    var found = false;
    for (var key : windowKeys(from, to, granularity)) {
      var value = buckets.get(key);
      if (value == null) continue;
      total = total.add(value);
      found = true;
    }
    return found ? total : null;
  }

  /** 增长率（基期缺失或为 0 ⇒ **算不出**，返回 null）。 */
  private BigDecimal growth(BigDecimal value, BigDecimal base) {
    if (value == null || base == null || base.signum() == 0) return null;
    return value
        .subtract(base)
        .divide(base, 8, RoundingMode.HALF_UP)
        .movePointRight(2)
        .setScale(4, RoundingMode.HALF_UP);
  }

  /** 偏离度（相对中位数）。 */
  private BigDecimal deviation(BigDecimal value, BigDecimal baseline) {
    if (value == null || baseline == null || baseline.signum() == 0) return null;
    return value.subtract(baseline).divide(baseline, 8, RoundingMode.HALF_UP);
  }

  /** 序列在窗口内的**中位数**（异常判定的基线）。 */
  private BigDecimal median(
      Map<LocalDateTime, BigDecimal> buckets, List<LocalDateTime> keys) {
    var values = new ArrayList<BigDecimal>();
    for (var key : keys) {
      var value = buckets.get(key);
      if (value != null) values.add(value);
    }
    if (values.isEmpty()) return null;
    values.sort(Comparator.naturalOrder());
    var middle = values.size() / 2;
    if (values.size() % 2 == 1) return values.get(middle);
    return values.get(middle - 1).add(values.get(middle)).divide(new BigDecimal("2"), 8, RoundingMode.HALF_UP);
  }

  // ==========================================================================
  // 事件
  // ==========================================================================

  /**
   * 取窗口内的事件。
   *
   * <p>⚠️ **有精确时刻的事件按本次请求的时区换算成日历日** —— 「北京时间 10-01 00:30 开促」
   * 在 UTC 口径下不会被算到前一天。
   */
  private List<ReportingSalesTrendModels.SalesTrendEvent> loadEvents(
      UUID tenantId,
      LocalDate from,
      LocalDate to,
      ZoneId zone,
      String shopCode,
      String skuCode) {
    var rows =
        sql.fetch(
            """
            select event_code, event_type, title, detail, event_date, end_date,
                   start_at, end_at, shop_code, sku_code, severity
              from reporting_sales_event
             where tenant_id = ?
               and end_date >= ? and event_date <= ?
             order by event_date, event_type, event_code
            """,
            tenantId,
            from.minusDays(RANGE_MARGIN_DAYS),
            to.plusDays(RANGE_MARGIN_DAYS));
    var out = new ArrayList<ReportingSalesTrendModels.SalesTrendEvent>();
    for (var row : rows) {
      var startAt = row.get(6, OffsetDateTime.class);
      var endAt = row.get(7, OffsetDateTime.class);
      var eventStart = row.get(4, LocalDate.class);
      var eventEnd = row.get(5, LocalDate.class);
      if (startAt != null && endAt != null) {
        eventStart = startAt.atZoneSameInstant(zone).toLocalDate();
        eventEnd = endAt.atZoneSameInstant(zone).toLocalDate();
      }
      if (eventStart == null || eventEnd == null) continue;
      var rowShopCode = row.get(8, String.class) == null ? "" : row.get(8, String.class);
      var rowSkuCode = row.get(9, String.class) == null ? "" : row.get(9, String.class);
      if (shopCode != null && !shopCode.isBlank() && !rowShopCode.isBlank() && !rowShopCode.equals(shopCode)) {
        continue;
      }
      if (skuCode != null && !skuCode.isBlank() && !rowSkuCode.isBlank() && !rowSkuCode.equals(skuCode)) {
        continue;
      }
      out.add(
          new ReportingSalesTrendModels.SalesTrendEvent(
              row.get(0, String.class),
              row.get(1, String.class),
              eventTypeLabel(row.get(1, String.class)),
              row.get(2, String.class),
              row.get(3, String.class),
              eventStart,
              eventEnd,
              rowShopCode,
              rowSkuCode,
              row.get(10, String.class)));
    }
    return out;
  }

  /** 落在某个桶里的事件（**空作用域 = 命中所有维度**）。 */
  private List<ReportingSalesTrendModels.SalesTrendEvent> matchingEvents(
      List<ReportingSalesTrendModels.SalesTrendEvent> events,
      LocalDate bucketStart,
      LocalDate bucketEnd,
      String shopCode,
      String skuCode) {
    var out = new ArrayList<ReportingSalesTrendModels.SalesTrendEvent>();
    for (var event : events) {
      if (event.eventDate().isAfter(bucketEnd) || event.endDate().isBefore(bucketStart)) continue;
      if (!shopCode.isBlank() && !event.shopCode().isBlank() && !event.shopCode().equals(shopCode)) {
        continue;
      }
      if (!skuCode.isBlank() && !event.skuCode().isBlank() && !event.skuCode().equals(skuCode)) {
        continue;
      }
      out.add(event);
    }
    return out;
  }

  /** 事件类型的中文标签。 */
  static String eventTypeLabel(String eventType) {
    return switch (eventType) {
      case "PROMOTION" -> "大促";
      case "FLASH_SALE" -> "秒杀";
      case "STOCKOUT" -> "断货";
      case "SHORTAGE" -> "缺货";
      default -> eventType;
    };
  }

  // ==========================================================================
  // 杂项
  // ==========================================================================

  /** 数据更新时间（**空表时返回 null**，不用假时间哨兵）。 */
  public OffsetDateTime dataUpdatedAt(UUID tenantId) {
    return tenants.run(
        tenantId,
        () -> {
          var factRow =
              sql.fetchOne(
                  "select max(updated_at) from reporting_sales_trend_fact where tenant_id = ?",
                  tenantId);
          var eventRow =
              sql.fetchOne(
                  "select max(updated_at) from reporting_sales_event where tenant_id = ?", tenantId);
          var factAt = factRow == null ? null : factRow.get(0, OffsetDateTime.class);
          var eventAt = eventRow == null ? null : eventRow.get(0, OffsetDateTime.class);
          if (factAt == null) return eventAt;
          if (eventAt == null) return factAt;
          return factAt.isAfter(eventAt) ? factAt : eventAt;
        });
  }

  /** 该租户**有没有任何**销量事实（不限窗口）。 */
  private boolean hasAnyFact(UUID tenantId) {
    var row = sql.fetchOne("select 1 from reporting_sales_trend_fact where tenant_id = ? limit 1", tenantId);
    return row != null;
  }

  /** 本位币折算用的汇率（**取每个币种最近的一条生效汇率**，并把时点一起返回）。 */
  private List<ReportingSalesTrendModels.SalesTrendCurrencyRate> rates(
      UUID tenantId, String baseCurrency, LocalDate to, Iterable<String> currencies) {
    var asOf = to.plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toOffsetDateTime();
    var rows =
        sql.fetch(
            """
            select distinct on (currency) currency, base_currency, rate, as_of
              from reporting_currency_rate_snapshot
             where tenant_id = ? and base_currency = ? and as_of <= ?::timestamptz
             order by currency, as_of desc
            """,
            tenantId,
            baseCurrency,
            asOf);
    var byCurrency = new LinkedHashMap<String, ReportingSalesTrendModels.SalesTrendCurrencyRate>();
    for (var row : rows) {
      var currency = row.get(0, String.class);
      byCurrency.put(
          currency,
          new ReportingSalesTrendModels.SalesTrendCurrencyRate(
              currency, row.get(1, String.class), row.get(2, BigDecimal.class), row.get(3, OffsetDateTime.class)));
    }
    var out = new ArrayList<ReportingSalesTrendModels.SalesTrendCurrencyRate>();
    for (var currency : currencies) {
      var rate = byCurrency.get(currency);
      if (rate != null) out.add(rate);
    }
    return out;
  }

  /** 某个币种的汇率。 */
  private ReportingSalesTrendModels.SalesTrendCurrencyRate rateFor(
      List<ReportingSalesTrendModels.SalesTrendCurrencyRate> rates, String currency) {
    for (var rate : rates) {
      if (rate.currency().equals(currency)) return rate;
    }
    return null;
  }

  /** 序列里出现过的币种。 */
  private List<String> seriesCurrencies(Map<SeriesKey, Map<LocalDateTime, BigDecimal>> series) {
    var out = new LinkedHashSet<String>();
    for (var entry : series.keySet()) out.add(entry.currency());
    return new ArrayList<>(out);
  }

  /** 该维度下要展示的指标（默认：全部基础口径）。 */
  private List<String> selectMetrics(
      Map<String, Definition> definitions, String dimensionType, List<String> metricCodes) {
    if (metricCodes != null && !metricCodes.isEmpty()) {
      var out = new ArrayList<String>();
      for (var code : metricCodes) {
        var definition = definitions.get(code);
        if (definition == null) continue;
        // ⚠️ **指标与维度必须对得上**（口径表里登记的维度）——
        //   拿公司口径的成交金额去看店铺维度就是口径错位。
        if (!definition.dimensionType().equals(dimensionType)) continue;
        out.add(code);
      }
      return out;
    }
    var out = new ArrayList<String>();
    for (var code : ReportingSalesTrendCatalog.baseMetricCodes()) {
      var definition = definitions.get(code);
      if (definition == null) continue;
      if (!definition.dimensionType().equals(dimensionType)) continue;
      out.add(code);
    }
    return out;
  }

  /** 口径视图（只列本模块用到的）。 */
  private List<ReportingSalesTrendModels.SalesTrendMetricDefinitionView> definitionViews(
      Map<String, Definition> definitions, List<String> metricCodes) {
    var out = new ArrayList<ReportingSalesTrendModels.SalesTrendMetricDefinitionView>();
    for (var code : metricCodes) {
      var definition = definitions.get(code);
      if (definition == null) continue;
      out.add(
          new ReportingSalesTrendModels.SalesTrendMetricDefinitionView(
              definition.metricCode(),
              definition.displayName(),
              definition.unit(),
              definition.aggregation(),
              definition.baseMetricCode(),
              definition.dimensionType(),
              definition.currencyAware(),
              definition.description(),
              definition.ownerModule()));
    }
    // 对比口径也要在目录里（它们是口径表里登记过的）。
    for (var definition : definitions.values()) {
      if (definition.aggregation().equals("SUM")) continue;
      if (metricCodes.contains(definition.baseMetricCode())) {
        out.add(
            new ReportingSalesTrendModels.SalesTrendMetricDefinitionView(
                definition.metricCode(),
                definition.displayName(),
                definition.unit(),
                definition.aggregation(),
                definition.baseMetricCode(),
                definition.dimensionType(),
                definition.currencyAware(),
                definition.description(),
                definition.ownerModule()));
      }
    }
    return out;
  }

  /** 维度键的展示名。 */
  private String dimensionLabel(String dimensionType, String dimensionKey) {
    if (dimensionType.equals("TOTAL")) return "全部店铺";
    return dimensionKey;
  }

  /** 口径说明（页面上原样显示）。 */
  private List<String> caliberNotes(Map<String, Definition> definitions) {
    var out = new ArrayList<String>();
    out.add(
        "**公司维度（TOTAL）的三个基础口径沿用 13.1.1.1 的口径表**"
            + "（reporting_metric_definition / ReportingMetricCatalog），本模块**引用**它们，"
            + "**不另起一份定义** —— 同一个数只能有一个口径真相。");
    out.add(
        "店铺维度与商品维度的口径是**本模块新增**的（13.1.1.1 的口径表没有维度列，"
            + "且既有表不允许 ALTER）：商品维度用的是**订单明细行金额**，"
            + "与公司口径的订单总额**不是一个数**（订单级折扣 / 运费 / 税不摊到行）。");
    return out;
  }
}
