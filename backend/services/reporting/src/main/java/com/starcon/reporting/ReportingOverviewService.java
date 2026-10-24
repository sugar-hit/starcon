package com.starcon.reporting;

import com.starcon.tenancy.TenantTransactions;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Service;

/**
 * 13.1.1.1 经营总览的**读模型**（蓝图「输入」句：报表投影数据；时间范围与维度选择；
 * 用户数据范围）。
 *
 * <p>⚠️ **本类不重算口径**：口径（单位 / 聚合方式 / 分子分母）全部来自
 * {@code reporting_metric_definition}，本类只**按定义聚合**。派生型指标（毛利率 / 退货率）
 * 是**递归求值**出来的：先求分子分母，再相除 —— 而不是在这里写死一句 SQL 公式。
 * 租户改了口径，这里的结果自动跟着改。
 *
 * <p>⚠️ **「算不出来」与「等于 0」必须分开**：分母为 0、事实不存在、指标未登记，
 * 三种情况一律返回 {@code null} / {@code NO_DATA}，**不用 0 冒充**。
 * 销售额 0 与「这一格没算出来」在页面上长得一样，含义却相反。
 *
 * <p>⚠️ **不跨币种相加**：涉币指标按币种分别报。只有调用方**显式**指定本位币、
 * 且 {@code reporting_currency_rate_snapshot} 里有生效日不晚于窗口末的汇率时，
 * 才额外给出本位币合计，并把**汇率与时点**一起返回。
 */
@Service
public class ReportingOverviewService {

  /** 趋势图默认展示的指标（**流量型**才有趋势；存量型是时点值，画成曲线会误导）。 */
  static final List<String> TREND_METRICS =
      List.of(
          ReportingMetricCatalog.SALES_AMOUNT,
          ReportingMetricCatalog.ORDER_COUNT,
          ReportingMetricCatalog.UNIT_SOLD,
          ReportingMetricCatalog.AD_SPEND);

  /** 派生型求值的深度上限（毛利率 → 毛利额 → 销售额/成本 = 3 层）。 */
  private static final int MAX_DERIVE_DEPTH = 4;

  private final DSLContext sql;
  private final TenantTransactions tenants;

  public ReportingOverviewService(DSLContext sql, TenantTransactions tenants) {
    this.sql = sql;
    this.tenants = tenants;
  }

  // ==========================================================================
  // 指标口径目录
  // ==========================================================================

  /** 读该租户的**指标口径目录**（口径唯一的展示形态）。 */
  public List<ReportingOverviewModels.MetricDefinitionView> metricDefinitions(UUID tenantId) {
    return tenants.run(
        tenantId,
        () -> {
          ensureMetricDefinitions(tenantId);
          var rows =
              sql.fetch(
                  """
                  select metric_code, display_name, unit, aggregation,
                         numerator_metric_code, denominator_metric_code,
                         currency_aware, description, owner_module
                    from reporting_metric_definition
                   where tenant_id = ? and enabled
                   order by metric_code
                  """,
                  tenantId);
          var out = new ArrayList<ReportingOverviewModels.MetricDefinitionView>();
          for (var row : rows) {
            out.add(
                new ReportingOverviewModels.MetricDefinitionView(
                    row.get(0, String.class),
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
        });
  }

  /**
   * 确保该租户有标准口径（**幂等**）。
   *
   * <p>⚠️ 为什么读路径也要播种：口径是**租户自己的**标准口径，第一次用到这个租户时
   * 按服务层的标准份播种一份。租户改了口径，播种**不会**覆盖它（{@code on conflict do nothing}）。
   *
   * <p>不播种的话：一个还没投影过任何事实的租户打开经营总览会看到**一张卡片都没有** ——
   * 那会被误读成「这个模块没做」，而不是「这个租户还没有数据」。
   */
  private void ensureMetricDefinitions(UUID tenantId) {
    var rows =
        sql.fetch(
            "select 1 from reporting_metric_definition where tenant_id = ? limit 1", tenantId);
    if (!rows.isEmpty()) return;
    for (var definition : ReportingMetricCatalog.STANDARD) {
      sql.execute(
          """
          insert into reporting_metric_definition
            (id,tenant_id,metric_code,display_name,unit,aggregation,numerator_metric_code,
             denominator_metric_code,currency_aware,description,owner_module,enabled,created_at,updated_at)
          values (?,?,?,?,?,?,?,?,?,?,?,true,now(),now())
          on conflict (tenant_id, metric_code) do nothing
          """,
          com.starcon.tenancy.Ids.next(),
          tenantId,
          definition.metricCode(),
          definition.displayName(),
          definition.unit().name(),
          definition.aggregation().name(),
          definition.numeratorMetricCode(),
          definition.denominatorMetricCode(),
          definition.currencyAware(),
          definition.description(),
          definition.ownerModule());
    }
  }

  // ==========================================================================
  // 经营总览
  // ==========================================================================

  /**
   * 经营总览：指标卡 + 趋势图 + 异常提示 + 数据更新时间戳。
   *
   * @param scopeType 维度：COMPANY / LEGAL_ENTITY / SHOP
   * @param baseCurrency 本位币（可空 ⇒ 只按币种分别报，不做跨币种合计）
   */
  public ReportingOverviewModels.OverviewResponse overview(
      UUID tenantId,
      LocalDate from,
      LocalDate to,
      String scopeType,
      UUID legalEntityId,
      UUID shopId,
      String baseCurrency) {
    return tenants.run(
        tenantId,
        () -> {
          ensureMetricDefinitions(tenantId);
          var definitions = definitionMap(tenantId);
          var scope = scopeFilter("f", scopeType, legalEntityId, shopId);
          var previous = previousWindow(from, to);
          var yearAgo = yearAgoWindow(from, to);
          var memo = new LinkedHashMap<String, Map<String, BigDecimal>>();

          var cards = new ArrayList<ReportingOverviewModels.MetricCard>();
          var warnings = new ArrayList<String>();
          var currencyNote = new StringBuilder();

          for (var definition : definitions.values()) {
            var current = values(tenantId, definition.metricCode(), scope, from, to, definitions, memo, 0);
            var previousValues =
                values(tenantId, definition.metricCode(), scope, previous.from(), previous.to(), definitions, memo, 0);
            var yearAgoValues =
                values(tenantId, definition.metricCode(), scope, yearAgo.from(), yearAgo.to(), definitions, memo, 0);

            var availability = current.isEmpty() ? "NO_DATA" : "AVAILABLE";
            var values = new ArrayList<ReportingOverviewModels.CurrencyValue>();
            for (var entry : current.entrySet()) {
              if (entry.getKey().startsWith("__")) continue;
              values.add(new ReportingOverviewModels.CurrencyValue(entry.getKey(), entry.getValue()));
            }
            if (values.isEmpty()) availability = "NO_DATA";

            var base = baseTotal(tenantId, values, baseCurrency, to);
            if (base != null && values.size() > 1) {
              currencyNote
                  .append("多币种指标已按币种分别列出，并按 ")
                  .append(baseCurrency)
                  .append(" 折算合计（汇率与时点见指标卡）。");
            } else if (values.size() > 1) {
              currencyNote
                  .append("指标按币种分别列出，未跨币种相加（未指定本位币或缺汇率快照）。");
            }

            cards.add(
                new ReportingOverviewModels.MetricCard(
                    definition.metricCode(),
                    definition.displayName(),
                    definition.unit(),
                    definition.aggregation(),
                    definition.description(),
                    definition.ownerModule(),
                    availability,
                    values,
                    base == null ? null : base.total(),
                    base == null ? null : base.baseCurrency(),
                    base == null ? null : base.rate(),
                    base == null ? null : base.rateAsOf(),
                    comparison(
                        "环比（上一等长周期）",
                        previous.from(),
                        previous.to(),
                        previousValues,
                        values,
                        base == null ? null : base.baseCurrency()),
                    comparison(
                        "同比（去年同期）",
                        yearAgo.from(),
                        yearAgo.to(),
                        yearAgoValues,
                        values,
                        base == null ? null : base.baseCurrency()),
                    metricNote(definition, current)));
          }

          var trend = trend(tenantId, TREND_METRICS, scope, from, to);
          var timezones = timezones(tenantId, from, to);
          var dataUpdatedAt = dataUpdatedAt(tenantId);

          if (timezones.size() > 1) {
            warnings.add(
                "本窗口的数据跨越 "
                    + String.join("、", timezones)
                    + " 多个分桶时区，跨时区汇总的时间口径以各行自己的时区为准。");
          }
          warnings.add("数据更新时间取自投影落库时刻；报表不是实时的，请以该时刻判断数据新鲜度。");

          return new ReportingOverviewModels.OverviewResponse(
              scopeType,
              legalEntityId,
              shopId,
              from,
              to,
              cards,
              trend,
              dataUpdatedAt,
              "最近一次投影落库时刻（reporting_projection_event.applied_at 与事实行 updated_at 取较晚者）。",
              timezones,
              warnings,
              currencyNote.toString());
        });
  }

  // ==========================================================================
  // 趋势
  // ==========================================================================

  /** 趋势：逐日逐币种。**没有事实的那一天没有点**（不是 0）。 */
  public List<ReportingOverviewModels.TrendPoint> trend(
      UUID tenantId,
      List<String> metricCodes,
      String scopeType,
      UUID legalEntityId,
      UUID shopId,
      LocalDate from,
      LocalDate to) {
    var scope = scopeFilter("f", scopeType, legalEntityId, shopId);
    return trend(tenantId, metricCodes, scope, from, to);
  }

  /** 趋势（内部：作用域已解析）。 */
  private List<ReportingOverviewModels.TrendPoint> trend(
      UUID tenantId,
      List<String> metricCodes,
      ScopeFilter scope,
      LocalDate from,
      LocalDate to) {
    return tenants.run(
        tenantId,
        () -> {
          var out = new ArrayList<ReportingOverviewModels.TrendPoint>();
          for (var metricCode : metricCodes) {
            var params = new ArrayList<Object>();
            params.add(tenantId);
            params.add(metricCode);
            params.add(from);
            params.add(to);
            params.addAll(scope.params());
            var rows =
                sql.fetch(
                    "select f.bucket_date, f.currency, sum(f.value) "
                        + "from reporting_metric_fact f "
                        + "where f.tenant_id = ? and f.metric_code = ? "
                        + "and f.bucket_date >= ? and f.bucket_date <= ? "
                        + scope.sql()
                        + " group by f.bucket_date, f.currency "
                        + "order by f.bucket_date, f.currency",
                    params.toArray());
            for (var row : rows) {
              out.add(
                  new ReportingOverviewModels.TrendPoint(
                      metricCode, row.get(0, LocalDate.class), row.get(1, String.class), row.get(2, BigDecimal.class)));
            }
          }
          return out;
        });
  }

  // ==========================================================================
  // 下钻
  // ==========================================================================

  /** 下钻构成明细（真分页 + 真计数）。 */
  public ReportingOverviewModels.DrilldownPage drilldown(
      UUID tenantId,
      String metricCode,
      String scopeType,
      UUID legalEntityId,
      UUID shopId,
      LocalDate from,
      LocalDate to,
      int page,
      int size) {
    return tenants.run(
        tenantId,
        () -> {
          var scope = scopeFilter("d", scopeType, legalEntityId, shopId);
          var where = new ArrayList<Object>();
          where.add(tenantId);
          where.add(metricCode);
          where.add(from);
          where.add(to);
          where.addAll(scope.params());
          var total =
              sql.fetchOne(
                  "select count(*) from reporting_drilldown_fact d "
                      + "where d.tenant_id = ? and d.metric_code = ? "
                      + "and d.bucket_date >= ? and d.bucket_date <= ? "
                      + scope.sql(),
                  where.toArray());
          var rows =
              sql.fetch(
                  "select d.source_kind, d.reference_id, d.reference_label, d.currency, "
                      + "d.quantity, d.value, d.bucket_date "
                      + "from reporting_drilldown_fact d "
                      + "where d.tenant_id = ? and d.metric_code = ? "
                      + "and d.bucket_date >= ? and d.bucket_date <= ? "
                      + scope.sql()
                      + " order by d.value desc, d.reference_id "
                      + "limit ? offset ?",
                  append(where, size, Math.max(page, 0) * size).toArray());
          var items = new ArrayList<ReportingOverviewModels.DrilldownRow>();
          for (var row : rows) {
            items.add(
                new ReportingOverviewModels.DrilldownRow(
                    row.get(0, String.class),
                    row.get(1, String.class),
                    row.get(2, String.class),
                    row.get(3, String.class),
                    row.get(4, BigDecimal.class),
                    row.get(5, BigDecimal.class),
                    row.get(6, LocalDate.class)));
          }
          var name = metricDisplayName(tenantId, metricCode);
          return new ReportingOverviewModels.DrilldownPage(
              items, Math.max(page, 0), size, total == null ? 0L : total.get(0, Long.class), metricCode, name);
        });
  }

  // ==========================================================================
  // 指标求值
  // ==========================================================================

  /**
   * 求一个指标在某个窗口、某个口径下的取值（按币种）。
   *
   * <p>⚠️ **递归**：派生型指标的分子分母本身可能是派生型（毛利率 → 毛利额 → 销售额/成本）。
   * 深度上限 {@value #MAX_DERIVE_DEPTH}，超了直接报错 —— 口径表被改坏时**要炸**，
   * 不能无限递归。
   */
  private Map<String, BigDecimal> values(
      UUID tenantId,
      String metricCode,
      ScopeFilter scope,
      LocalDate from,
      LocalDate to,
      Map<String, Definition> definitions,
      Map<String, Map<String, BigDecimal>> memo,
      int depth) {
    if (depth > MAX_DERIVE_DEPTH) {
      throw new IllegalStateException("指标派生层级超过上限，可能是口径表被改坏了：" + metricCode);
    }
    var key = metricCode + "|" + from + "|" + to + "|" + scope.key();
    var cached = memo.get(key);
    if (cached != null) return cached;
    var definition = definitions.get(metricCode);
    if (definition == null) {
      var empty = Map.<String, BigDecimal>of();
      memo.put(key, empty);
      return empty;
    }
    var result = new LinkedHashMap<String, BigDecimal>();
    switch (definition.aggregation()) {
      case "SUM" -> result.putAll(sumValues(tenantId, metricCode, scope, from, to));
      case "LAST" -> result.putAll(lastValues(tenantId, metricCode, scope, from, to));
      case "DERIVED_DIFF" -> {
        var numerator =
            values(tenantId, definition.numeratorMetricCode(), scope, from, to, definitions, memo, depth + 1);
        var denominator =
            values(tenantId, definition.denominatorMetricCode(), scope, from, to, definitions, memo, depth + 1);
        for (var entry : numerator.entrySet()) {
          var other = denominator.get(entry.getKey());
          // ⚠️ 分子分母**任一缺失就不算**（不用 0 冒充）。
          if (other == null) continue;
          result.put(entry.getKey(), entry.getValue().subtract(other));
        }
      }
      case "DERIVED_RATIO" -> {
        var numerator =
            values(tenantId, definition.numeratorMetricCode(), scope, from, to, definitions, memo, depth + 1);
        var denominator =
            values(tenantId, definition.denominatorMetricCode(), scope, from, to, definitions, memo, depth + 1);
        for (var entry : numerator.entrySet()) {
          var other = denominator.get(entry.getKey());
          if (other == null || other.signum() == 0) continue;
          var ratio = entry.getValue().divide(other, 8, RoundingMode.HALF_UP);
          if ("PERCENT".equals(definition.unit())) {
            ratio = ratio.movePointRight(2).setScale(4, RoundingMode.HALF_UP);
          }
          result.put(entry.getKey(), ratio);
        }
      }
      default -> {}
    }
    memo.put(key, result);
    return result;
  }

  /** 流量型：窗口内把贡献行相加。 */
  private Map<String, BigDecimal> sumValues(
      UUID tenantId, String metricCode, ScopeFilter scope, LocalDate from, LocalDate to) {
    var params = new ArrayList<Object>();
    params.add(tenantId);
    params.add(metricCode);
    params.add(from);
    params.add(to);
    params.addAll(scope.params());
    var rows =
        sql.fetch(
            "select f.currency, sum(f.value) from reporting_metric_fact f "
                + "where f.tenant_id = ? and f.metric_code = ? "
                + "and f.bucket_date >= ? and f.bucket_date <= ? "
                + scope.sql()
                + " group by f.currency",
            params.toArray());
    var out = new LinkedHashMap<String, BigDecimal>();
    for (var row : rows) {
      var value = row.get(1, BigDecimal.class);
      if (value != null) out.put(row.get(0, String.class), value);
    }
    return out;
  }

  /**
   * 存量型：取窗口内**最近一天**的快照值（余额不能累加）。
   *
   * <p>「最近一天」按**币种各自**取：本仓没有汇率源，USD 有 25 号的快照而 EUR 只有
   * 20 号时，两个币种各取自己的最近值，**不假装它们是同一天**。
   */
  private Map<String, BigDecimal> lastValues(
      UUID tenantId, String metricCode, ScopeFilter scope, LocalDate from, LocalDate to) {
    var params = new ArrayList<Object>();
    params.add(tenantId);
    params.add(metricCode);
    params.add(to);
    params.addAll(scope.params());
    params.add(to);
    params.addAll(scope.params());
    var rows =
        sql.fetch(
            "select f.currency, sum(f.value) "
                + "from reporting_metric_fact f "
                + "where f.tenant_id = ? and f.metric_code = ? and f.bucket_date <= ? "
                + scope.sql()
                + " and f.bucket_date = ("
                + "   select max(f2.bucket_date) from reporting_metric_fact f2 "
                + "    where f2.tenant_id = f.tenant_id and f2.metric_code = f.metric_code "
                + "      and f2.currency = f.currency and f2.bucket_date <= ? "
                + scope.sql("f2")
                + " ) "
                + "group by f.currency",
            params.toArray());
    var out = new LinkedHashMap<String, BigDecimal>();
    for (var row : rows) {
      var value = row.get(1, BigDecimal.class);
      if (value != null) out.put(row.get(0, String.class), value);
    }
    return out;
  }

  // ==========================================================================
  // 跨币种折算
  // ==========================================================================

  /** 本位币合计（含用的汇率与时点）。 */
  private record BaseTotal(BigDecimal total, String baseCurrency, BigDecimal rate, OffsetDateTime rateAsOf) {}

  /**
   * 折算成本位币合计。
   *
   * <p>⚠️ **汇率必须带时点**：按窗口末日取**最近的那条**快照，并把汇率与时点一起返回 ——
   * 「今天的汇率变了不能让上月的报表变」。
   */
  private BaseTotal baseTotal(
      UUID tenantId,
      List<ReportingOverviewModels.CurrencyValue> values,
      String baseCurrency,
      LocalDate windowEnd) {
    if (baseCurrency == null || baseCurrency.isBlank() || values.isEmpty()) return null;
    var total = BigDecimal.ZERO;
    BigDecimal representativeRate = null;
    OffsetDateTime representativeAsOf = null;
    for (var value : values) {
      if (value.value() == null) continue;
      if (value.currency().equals(baseCurrency)) {
        total = total.add(value.value());
        continue;
      }
      var rate = resolveRate(tenantId, value.currency(), baseCurrency, windowEnd);
      if (rate == null) return null;
      total = total.add(value.value().multiply(rate.rate()).setScale(4, RoundingMode.HALF_UP));
      if (representativeRate == null) {
        representativeRate = rate.rate();
        representativeAsOf = rate.asOf();
      }
    }
    return new BaseTotal(total, baseCurrency, representativeRate, representativeAsOf);
  }

  /** 一条汇率快照。 */
  private record RateSnapshot(BigDecimal rate, OffsetDateTime asOf) {}

  /** 取生效日不晚于 {@code asOf} 的最近一条汇率快照。 */
  private RateSnapshot resolveRate(UUID tenantId, String currency, String baseCurrency, LocalDate asOf) {
    var row =
        sql.fetchOne(
            "select rate, as_of from reporting_currency_rate_snapshot "
                + "where tenant_id = ? and currency = ? and base_currency = ? "
                + "and as_of <= ?::timestamptz "
                + "order by as_of desc limit 1",
            tenantId,
            currency,
            baseCurrency,
            asOf.atStartOfDay(java.time.ZoneOffset.UTC).toOffsetDateTime());
    if (row == null) return null;
    return new RateSnapshot(row.get(0, BigDecimal.class), row.get(1, OffsetDateTime.class));
  }

  // ==========================================================================
  // 对比
  // ==========================================================================

  /** 对比期。 */
  private record Window(LocalDate from, LocalDate to) {}

  /** 环比窗口：紧邻的等长周期。 */
  private Window previousWindow(LocalDate from, LocalDate to) {
    var length = java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1;
    return new Window(from.minusDays(length), from.minusDays(1));
  }

  /** 同比窗口：去年同期。 */
  private Window yearAgoWindow(LocalDate from, LocalDate to) {
    return new Window(from.minusYears(1), to.minusYears(1));
  }

  /** 对比：给出对比期的取值与变化率。 */
  private ReportingOverviewModels.Comparison comparison(
      String label,
      LocalDate from,
      LocalDate to,
      Map<String, BigDecimal> previousValues,
      List<ReportingOverviewModels.CurrencyValue> currentValues,
      String baseCurrency) {
    var values = new ArrayList<ReportingOverviewModels.CurrencyValue>();
    for (var entry : previousValues.entrySet()) {
      values.add(new ReportingOverviewModels.CurrencyValue(entry.getKey(), entry.getValue()));
    }
    // 变化率只在**能落到一个数**时给：单币种，或已给出本位币合计。
    String onlyCurrency = null;
    if (currentValues.size() == 1) {
      onlyCurrency = currentValues.get(0).currency();
    } else if (baseCurrency != null) {
      onlyCurrency = baseCurrency;
    }
    BigDecimal changeRate = null;
    BigDecimal changeAmount = null;
    var note = "";
    if (onlyCurrency == null) {
      note = "多币种指标没有单一的变化率，请按币种分别比较。";
    } else {
      var previous = previousValues.get(onlyCurrency);
      // ⚠️ onlyCurrency 在上面的分支里被赋值过 ⇒ 不是「事实上 final」，
      // 不能直接被 lambda 捕获，所以先复制一份 final 的。
      final var currencyKey = onlyCurrency;
      var current =
          currentValues.stream()
              .filter(v -> v.currency().equals(currencyKey))
              .map(ReportingOverviewModels.CurrencyValue::value)
              .findFirst()
              .orElse(null);
      if (previous == null || current == null) {
        note = "对比期或本期缺少这个币种的数据，无法计算变化率。";
      } else if (previous.signum() == 0) {
        note = "对比期取值为 0，变化率不可比（不显示成 0%）。";
      } else {
        changeAmount = current.subtract(previous).setScale(4, RoundingMode.HALF_UP);
        changeRate =
            changeAmount
                .divide(previous.abs(), 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(2, RoundingMode.HALF_UP);
      }
    }
    return new ReportingOverviewModels.Comparison(
        label, from, to, values, changeRate, changeAmount, changeRate != null, note);
  }

  // ==========================================================================
  // 杂项
  // ==========================================================================

  /** 数据更新时间（卷 13 卷首「数据延迟要显示」）。 */
  public OffsetDateTime dataUpdatedAt(UUID tenantId) {
    return tenants.run(
        tenantId,
        () -> {
          var factRow =
              sql.fetchOne(
                  "select max(updated_at) from reporting_metric_fact where tenant_id = ?", tenantId);
          var eventRow =
              sql.fetchOne(
                  "select max(applied_at) from reporting_projection_event where tenant_id = ?", tenantId);
          var factAt = factRow == null ? null : factRow.get(0, OffsetDateTime.class);
          var eventAt = eventRow == null ? null : eventRow.get(0, OffsetDateTime.class);
          if (factAt == null) return eventAt;
          if (eventAt == null) return factAt;
          return factAt.isAfter(eventAt) ? factAt : eventAt;
        });
  }

  /** 本窗口用到的分桶时区（**跨时区汇总必须显示所用时区**）。 */
  public List<String> timezones(UUID tenantId, LocalDate from, LocalDate to) {
    return tenants.run(
        tenantId,
        () -> {
          var rows =
              sql.fetch(
                  "select distinct timezone from reporting_metric_fact "
                      + "where tenant_id = ? and bucket_date >= ? and bucket_date <= ? "
                      + "order by timezone",
                  tenantId,
                  from,
                  to);
          var out = new ArrayList<String>();
          for (var row : rows) {
            var zone = row.get(0, String.class);
            if (zone != null && !zone.isBlank()) out.add(zone);
          }
          return out;
        });
  }

  /** 指标显示名（取不到就用指标码本身，不编名字）。 */
  private String metricDisplayName(UUID tenantId, String metricCode) {
    var definitions = definitionMap(tenantId);
    var definition = definitions.get(metricCode);
    return definition == null ? metricCode : definition.displayName();
  }

  /** 指标卡的口径说明 / 备注。 */
  private String metricNote(Definition definition, Map<String, BigDecimal> values) {
    if (values.isEmpty()) {
      return "该口径下**没有事实数据**（不是 0）：可能是上游还没有投影，或该维度不带这类事实的归属。";
    }
    return "";
  }

  // ==========================================================================
  // 内部
  // ==========================================================================

  /** 口径定义（运行时的唯一真相来自 DB；这里只是它的内存镜像）。 */
  private record Definition(
      String metricCode,
      String displayName,
      String unit,
      String aggregation,
      String numeratorMetricCode,
      String denominatorMetricCode,
      boolean currencyAware,
      String description,
      String ownerModule) {}

  private Map<String, Definition> definitionMap(UUID tenantId) {
    var rows =
        sql.fetch(
            """
            select metric_code, display_name, unit, aggregation,
                   numerator_metric_code, denominator_metric_code,
                   currency_aware, description, owner_module
              from reporting_metric_definition
             where tenant_id = ? and enabled
             order by metric_code
            """,
            tenantId);
    var out = new LinkedHashMap<String, Definition>();
    for (var row : rows) {
      out.put(
          row.get(0, String.class),
          new Definition(
              row.get(0, String.class),
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

  /** 作用域过滤（SQL 片段 + 绑定参数）。 */
  private record ScopeFilter(String clause, List<Object> params, String key) {
    /** 当前别名的过滤片段。 */
    String sql() {
      return clause;
    }

    /** 换别名的过滤片段（子查询里要用 f2）。 */
    String sql(String alias) {
      return clause.replace("f.", alias + ".");
    }
  }

  private ScopeFilter scopeFilter(
      String alias, String scopeType, UUID legalEntityId, UUID shopId) {
    var params = new ArrayList<Object>();
    var sb = new StringBuilder();
    sb.append(" and ").append(alias).append(".scope_type = ?");
    params.add(scopeType);
    if ("LEGAL_ENTITY".equals(scopeType) && legalEntityId != null) {
      sb.append(" and ").append(alias).append(".legal_entity_id = ?");
      params.add(legalEntityId);
    }
    if ("SHOP".equals(scopeType) && shopId != null) {
      sb.append(" and ").append(alias).append(".shop_id = ?");
      params.add(shopId);
    }
    return new ScopeFilter(sb.toString(), params, scopeType + "|" + legalEntityId + "|" + shopId);
  }

  private static List<Object> append(List<Object> base, Object... extra) {
    var out = new ArrayList<>(base);
    out.addAll(List.of(extra));
    return out;
  }
}
