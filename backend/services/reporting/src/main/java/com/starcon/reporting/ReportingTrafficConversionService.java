package com.starcon.reporting;

import com.starcon.tenancy.TenantTransactions;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Service;

/**
 * 13.3.2.1 流量转化的**查询侧**（漏斗视图 + 转化率趋势 + 异常诊断 + 构成明细下钻）。
 *
 * <p>⚠️ **口径唯一**：指标定义从 {@code reporting_traffic_conversion_metric_definition} 读，
 * 查询 SQL **不重算口径**，只按定义聚合。派生指标（比率 / 差值）**只写引用**（分子分母的指标码），
 * 口径改一处，引用它的地方自动跟着改。
 *
 * <p>⚠️ **两套口径不许混算**（本模块的第一性纪律）：
 *
 * <ul>
 *   <li>每个事实按 {@code traffic_source} 分桶（LISTING / AD / ORDER）⇒ 聚合时**不跨桶相加**；
 *   <li>每个值都带 {@code caliber} 标签（PLATFORM / ORDER）⇒ 页面上能一眼看出它是谁家的数；
 *   <li>「自然流量」是**派生差**（LISTING − AD）⇒ 相减为负时报「口径冲突」，**绝不截成 0**；
 *   <li>「转化率」有两个口径，订单口径**算不出来**（分母缺失不是 0）⇒ 「不可比」。
 * </ul>
 *
 * <p>⚠️ **「算不出来」与「等于 0」必须分开**：分母为 0、基线缺失、事实不存在，
 * 一律返回 {@code null} / {@code NO_DATA} / {@code NOT_COMPARABLE}，**不用 0 冒充**。
 */
@Service
public class ReportingTrafficConversionService {

  /** 比率型指标的显示精度（百分比保留 4 位小数）。 */
  private static final int PERCENT_SCALE = 4;

  /** 「没有事实」的说明。 */
  private static final String NO_DATA_NOTE = "这个窗口里没有这个指标的事实数据。";

  /** 「分母缺失或为 0」的说明。 */
  private static final String NOT_COMPARABLE_NOTE = "分母缺失或为 0，算不出来（不是 0%）。";

  /** 趋势里要出的指标（**全部与货币无关** ⇒ 桶里不掺币种）。 */
  private static final List<String> TREND_METRICS =
      List.of(
          ReportingTrafficConversionCatalog.IMPRESSIONS,
          ReportingTrafficConversionCatalog.CLICKS,
          ReportingTrafficConversionCatalog.SESSIONS,
          ReportingTrafficConversionCatalog.PLATFORM_UNITS,
          ReportingTrafficConversionCatalog.CTR,
          ReportingTrafficConversionCatalog.CONVERSION_RATE,
          ReportingTrafficConversionCatalog.AD_IMPRESSIONS,
          ReportingTrafficConversionCatalog.AD_CLICKS,
          ReportingTrafficConversionCatalog.AD_ORDERS,
          ReportingTrafficConversionCatalog.AD_CONVERSION_RATE,
          ReportingTrafficConversionCatalog.ORDER_COUNT);

  private final DSLContext sql;
  private final TenantTransactions tenants;
  private final ReportingTrafficConversionRefreshService refresh;

  public ReportingTrafficConversionService(
      DSLContext sql,
      TenantTransactions tenants,
      ReportingTrafficConversionRefreshService refresh) {
    this.sql = sql;
    this.tenants = tenants;
    this.refresh = refresh;
  }

  // ==========================================================================
  // 指标口径目录
  // ==========================================================================

  /** 指标口径目录（**运行时唯一的真相是那张表**）。 */
  public List<ReportingTrafficConversionModels.TrafficMetricDefinitionView> metricDefinitions(
      UUID tenantId) {
    return tenants.run(
        tenantId,
        () -> {
          // ⚠️ 口径表是空的时先播种一份标准口径（与 13.2.2.1 同纪律）——
          //   否则新租户第一次打开口径目录看到的是空列表。
          refresh.seedDefinitions(tenantId);
          return definitionViews(definitionMap(tenantId));
        });
  }

  // ==========================================================================
  // 主报表
  // ==========================================================================

  /**
   * 流量转化报表：漏斗 + 关键指标 + 转化率趋势 + 异常诊断。
   *
   * @param granularity DAY / WEEK / MONTH（**桶的对齐规则是口径的一部分**）
   */
  public ReportingTrafficConversionModels.TrafficConversionResponse report(
      UUID tenantId, LocalDate from, LocalDate to, String granularity) {
    var g = ReportingSalesTrendGranularity.of(granularity);
    var unit = g == null ? ReportingSalesTrendGranularity.DAY : g;

    return tenants.run(
        tenantId,
        () -> {
          refresh.seedDefinitions(tenantId);
          var definitions = definitionMap(tenantId);
          var facts = loadFacts(tenantId, from, to);
          var bucketFacts = loadBucketFacts(tenantId, from, to, unit);

          var funnel = funnelGroups(definitions, facts);
          var keyMetrics = keyMetrics(definitions, facts);
          var trend = trend(definitions, bucketFacts, unit, from, to);
          var diagnoses = diagnose(definitions, facts, bucketFacts, unit, from, to);

          var updatedAt = dataUpdatedAt(tenantId);
          var warnings = new ArrayList<String>();
          var sources = presentSources(facts);
          if (!sources.contains("LISTING")) {
            warnings.add(
                "⚠️ 这个窗口**没有平台商品口径**的事实（2 同步的平台表现没投进来）—— "
                    + "平台漏斗与自然流量（推导值）都会缺一环。");
          }
          if (!sources.contains("AD")) {
            warnings.add(
                "⚠️ 这个窗口**没有广告口径**的事实（14 广告的活动报表没投进来）—— "
                    + "广告漏斗是空的，**自然流量（推导值）也算不出来**"
                    + "（平台口径 − 广告口径，缺一 operand）。");
          }
          if (!sources.contains("ORDER")) {
            warnings.add(
                "⚠️ 这个窗口**没有订单口径**的事实（9 订单没投进来）—— "
                    + "订单口径的客单价与「口径背离」诊断都算不出来。");
          }
          for (var diagnosis : diagnoses) {
            if (ReportingTrafficConversionCatalog.RULE_CALIBER_GAP.equals(diagnosis.ruleCode())) {              warnings.add(
                  "⚠️ **口径背离**："
                      + diagnosis.message()
                      + "（这是两个口径统计范围不同导致的，**不是数据错**，但必须被看见）");
            }
          }

          var empty = facts.isEmpty();
          return new ReportingTrafficConversionModels.TrafficConversionResponse(
              from,
              to,
              unit.name(),
              unit.label(),
              funnel,
              keyMetrics,
              trend,
              diagnoses,
              definitionViews(definitions),
              updatedAt,
              updatedAt == null
                  ? "本租户还没有任何流量转化投影数据（空表时**不显示**假更新时间）。"
                  : "数据更新时间 = 最近一次投影落库时刻（报表是投影，不是实时的）。",
              empty ? "EMPTY" : "OK",
              empty
                  ? "这个窗口内没有任何流量/转化事实。"
                  : "已按 "
                      + unit.label()
                      + "粒度给出漏斗、趋势与诊断；⚠️ **两套口径分列，不混算**。",
              empty ? "NO_FACTS_IN_WINDOW" : null,
              empty
                  ? "这个窗口内没有可分析的流量事实。可能是这段时间平台没回数据，"
                      + "也可能是**还没跑过投影刷新** —— 两者页面上长得一样，所以这里如实说明。"
                  : "",
              warnings,
              ReportingTrafficConversionCatalog.CALIBER_NOTES);
        });
  }

  // ==========================================================================
  // 漏斗
  // ==========================================================================

  /** 漏斗分组（每一节都带自己的口径与可用性）。 */
  private List<ReportingTrafficConversionModels.TrafficFunnelGroup> funnelGroups(
      Map<String, ReportingTrafficConversionCatalog.Definition> definitions,
      Map<String, Agg> facts) {
    var out = new ArrayList<ReportingTrafficConversionModels.TrafficFunnelGroup>();
    for (var group : ReportingTrafficConversionCatalog.FUNNEL_GROUPS) {
      var stages = new ArrayList<ReportingTrafficConversionModels.TrafficFunnelStage>();
      BigDecimal first = null;
      BigDecimal previous = null;
      for (var stage : group.stages()) {
        var definition = definitions.get(stage.metricCode());
        var displayName =
            definition == null ? stage.displayName() : definition.displayName();
        var caliber = definition == null ? group.caliber() : definition.caliber().name();
        var trafficSource =
            definition == null || definition.trafficSourceName() == null
                ? group.trafficSource()
                : definition.trafficSourceName();

        BigDecimal value;
        String availability;
        String note;
        if (definition == null) {
          value = null;
          availability = "NO_DATA";
          note = "这个指标还没有在口径表里登记。";
        } else if (definition.aggregation() == ReportingTrafficConversionCatalog.Aggregation.SUM) {
          // 🔴 件数与货币无关 ⇒ 跨币种合计（漏斗里每一节的币种都显示空串 = 与币种无关）。
          value = crossTotal(facts, definition.trafficSourceName(), definition.metricCode());
          availability = value == null ? "NO_DATA" : "AVAILABLE";
          note = value == null ? NO_DATA_NOTE : "";
        } else {
          // 派生差（自然流量）：平台商品口径 − 广告口径。
          var numerator = definition.numeratorMetricCode() == null
              ? null
              : definitions.get(definition.numeratorMetricCode());
          var denominator = definition.denominatorMetricCode() == null
              ? null
              : definitions.get(definition.denominatorMetricCode());
          if (numerator == null || denominator == null) {
            value = null;
            availability = "NOT_COMPARABLE";
            note = "口径表里缺分子或分母指标，口径不成立。";
          } else {
            var numeratorValue = crossValue(facts, numerator);
            var denominatorValue = crossValue(facts, denominator);
            if (numeratorValue == null || denominatorValue == null) {
              value = null;
              availability = "NO_DATA";
              note = "推导值缺一 operand（平台口径或广告口径在这个窗口没有事实）。";
            } else {
              value = numeratorValue.subtract(denominatorValue);
              if (value.signum() < 0) {
                availability = "CALIBER_CONFLICT";
                note =
                    "🔴 两套平台口径相减为负（"
                        + value.toPlainString()
                        + "）⇒ 它们的统计范围不一致，这个推导值不可用。"
                        + "**没有截成 0**（截成 0 会把口径问题藏起来）。";
              } else {
                availability = "AVAILABLE";
                note = "推导值 = 平台商品口径 − 广告口径。";
              }
            }
          }
        }

        BigDecimal stepRate = null;
        BigDecimal overallRate = null;
        if (value != null && availability.equals("AVAILABLE")) {
          if (previous != null && previous.signum() != 0) {
            stepRate = value.divide(previous, PERCENT_SCALE, RoundingMode.HALF_UP);
          }
          if (first == null) {
            first = value;
          } else if (first.signum() != 0) {
            overallRate = value.divide(first, PERCENT_SCALE, RoundingMode.HALF_UP);
          }
          previous = value;
        }

        stages.add(
            new ReportingTrafficConversionModels.TrafficFunnelStage(
                stage.stageKey(),
                displayName,
                stage.metricCode(),
                definition == null ? "QUANTITY" : definition.unit().name(),
                caliber,
                trafficSource,
                "",
                value,
                stepRate,
                overallRate,
                availability,
                note));
      }
      out.add(
          new ReportingTrafficConversionModels.TrafficFunnelGroup(
              group.groupKey(),
              group.displayName(),
              group.caliber(),
              group.trafficSource(),
              group.sourceNote(),
              List.copyOf(stages),
              "「—」= 算不出来，不是 0。"));
    }
    return List.copyOf(out);
  }

  // ==========================================================================
  // 关键指标
  // ==========================================================================

  /** 窗口内全部指标的取值（**每个都带口径标签**）。 */
  private List<ReportingTrafficConversionModels.TrafficMetricValue> keyMetrics(
      Map<String, ReportingTrafficConversionCatalog.Definition> definitions,
      Map<String, Agg> facts) {
    var out = new ArrayList<ReportingTrafficConversionModels.TrafficMetricValue>();
    for (var definition : definitions.values()) {
      switch (definition.aggregation()) {
        case SUM -> {
          if (definition.currencyAware()) {
            var currencies = currenciesOf(facts, definition);
            if (currencies.isEmpty()) {
              out.add(sumValue(definition, "", null));
            } else {
              for (var currency : currencies) {
                var agg = facts.get(key(definition.trafficSourceName(), definition.metricCode(), currency));
                out.add(sumValue(definition, currency, agg == null ? null : agg.value()));
              }
            }
          } else {
            // 🔴 **件数与货币无关 ⇒ 跨币种合计**（币种显示为空串 = 与币种无关，不是「币种未知」）。
            var total = crossTotal(facts, definition.trafficSourceName(), definition.metricCode());
            out.add(sumValue(definition, "", total));
          }
        }
        case DERIVED_RATIO -> {
          var numerator = definitions.get(definition.numeratorMetricCode());
          var denominator = definitions.get(definition.denominatorMetricCode());
          if (numerator == null || denominator == null) {
            out.add(
                new ReportingTrafficConversionModels.TrafficMetricValue(
                    definition.metricCode(),
                    definition.displayName(),
                    definition.unit().name(),
                    definition.caliber().name(),
                    null,
                    "",
                    null,
                    "NOT_COMPARABLE",
                    "口径表里缺分子或分母指标，口径不成立。"));
            continue;
          }
          if (definition.currencyAware()) {
            // 🔴 **金额除以量，必须同币种**：分子分母都取**同一个币种桶** ⇒ 客单价是精确的。
            var currencies = currenciesOf(facts, numerator);
            if (currencies.isEmpty()) {
              out.add(ratioValue(definition, "", null, null));
            } else {
              for (var currency : currencies) {
                var numeratorValue = bucketValue(facts, numerator, currency);
                var denominatorValue = bucketValue(facts, denominator, currency);
                out.add(ratioValue(definition, currency, numeratorValue, denominatorValue));
              }
            }
          } else {
            // 🔴 与货币无关的比率（点击率 / 转化率）：分子分母都取**跨币种合计**。
            var numeratorValue = crossValue(facts, numerator);
            var denominatorValue = crossValue(facts, denominator);
            out.add(ratioValue(definition, "", numeratorValue, denominatorValue));
          }
        }
        case DERIVED_DIFF -> {
          var numerator = definitions.get(definition.numeratorMetricCode());
          var denominator = definitions.get(definition.denominatorMetricCode());
          if (numerator == null || denominator == null) {
            out.add(
                new ReportingTrafficConversionModels.TrafficMetricValue(
                    definition.metricCode(),
                    definition.displayName(),
                    definition.unit().name(),
                    definition.caliber().name(),
                    null,
                    "",
                    null,
                    "NOT_COMPARABLE",
                    "口径表里缺分子或分母指标，口径不成立。"));
            continue;
          }
          var numeratorValue = crossValue(facts, numerator);
          var denominatorValue = crossValue(facts, denominator);
          if (numeratorValue == null || denominatorValue == null) {
            out.add(
                new ReportingTrafficConversionModels.TrafficMetricValue(
                    definition.metricCode(),
                    definition.displayName(),
                    definition.unit().name(),
                    definition.caliber().name(),
                    null,
                    "",
                    null,
                    "NO_DATA",
                    "推导值缺一 operand（平台口径或广告口径在这个窗口没有事实）。"));
            continue;
          }
          var value = numeratorValue.subtract(denominatorValue);
          var conflict = value.signum() < 0;
          out.add(
              new ReportingTrafficConversionModels.TrafficMetricValue(
                  definition.metricCode(),
                  definition.displayName(),
                  definition.unit().name(),
                  definition.caliber().name(),
                  null,
                  "",
                  value,
                  conflict ? "CALIBER_CONFLICT" : "AVAILABLE",
                  conflict
                      ? "🔴 两套平台口径相减为负 ⇒ 统计范围不一致，这个推导值不可用。"
                          + "**没有截成 0**。"
                      : "推导值 = 平台商品口径 − 广告口径。"));
        }
        default -> {}
      }
    }
    return List.copyOf(out);
  }

  private ReportingTrafficConversionModels.TrafficMetricValue sumValue(
      ReportingTrafficConversionCatalog.Definition definition, String currency, BigDecimal value) {
    return new ReportingTrafficConversionModels.TrafficMetricValue(
        definition.metricCode(),
        definition.displayName(),
        definition.unit().name(),
        definition.caliber().name(),
        definition.trafficSourceName(),
        currency,
        value,
        value == null ? "NO_DATA" : "AVAILABLE",
        value == null ? NO_DATA_NOTE : "");
  }

  private ReportingTrafficConversionModels.TrafficMetricValue ratioValue(
      ReportingTrafficConversionCatalog.Definition definition,
      String currency,
      BigDecimal numerator,
      BigDecimal denominator) {
    if (numerator == null || denominator == null) {
      return new ReportingTrafficConversionModels.TrafficMetricValue(
          definition.metricCode(),
          definition.displayName(),
          definition.unit().name(),
          definition.caliber().name(),
          null,
          currency,
          null,
          "NO_DATA",
          NO_DATA_NOTE);
    }
    if (denominator.signum() == 0) {
      return new ReportingTrafficConversionModels.TrafficMetricValue(
          definition.metricCode(),
          definition.displayName(),
          definition.unit().name(),
          definition.caliber().name(),
          null,
          currency,
          null,
          "NOT_COMPARABLE",
          NOT_COMPARABLE_NOTE);
    }
    return new ReportingTrafficConversionModels.TrafficMetricValue(
        definition.metricCode(),
        definition.displayName(),
        definition.unit().name(),
        definition.caliber().name(),
        null,
        currency,
        numerator.divide(denominator, PERCENT_SCALE, RoundingMode.HALF_UP),
        "AVAILABLE",
        "");
  }

  // ==========================================================================
  // 趋势
  // ==========================================================================

  /** 转化率趋势（**桶的对齐规则由粒度决定**：日 / ISO 周 / 自然月）。 */
  private List<ReportingTrafficConversionModels.TrafficTrendPoint> trend(
      Map<String, ReportingTrafficConversionCatalog.Definition> definitions,
      Map<String, Agg> bucketFacts,
      ReportingSalesTrendGranularity granularity,
      LocalDate from,
      LocalDate to) {
    var out = new ArrayList<ReportingTrafficConversionModels.TrafficTrendPoint>();
    var buckets = new LinkedHashMap<LocalDate, LocalDate>();
    var start = granularity.alignStart(from);
    var end = granularity.alignEnd(to);
    for (var cursor = start; !cursor.isAfter(end); cursor = nextBucket(cursor, granularity)) {
      buckets.put(cursor, cursor);
    }
    for (var bucketStart : buckets.keySet()) {
      var bucketEnd = granularity.alignEnd(bucketStart);
      var metrics = new ArrayList<ReportingTrafficConversionModels.TrafficMetricValue>();
      for (var metricCode : TREND_METRICS) {
        var definition = definitions.get(metricCode);
        if (definition == null) continue;
        if (definition.aggregation() == ReportingTrafficConversionCatalog.Aggregation.SUM) {
          var agg = bucketFacts.get(bucketKey(bucketStart, definition.trafficSourceName(), metricCode));
          metrics.add(
              new ReportingTrafficConversionModels.TrafficMetricValue(
                  definition.metricCode(),
                  definition.displayName(),
                  definition.unit().name(),
                  definition.caliber().name(),
                  definition.trafficSourceName(),
                  "",
                  agg == null ? null : agg.quantity(),
                  agg == null ? "NO_DATA" : "AVAILABLE",
                  agg == null ? NO_DATA_NOTE : ""));
        } else {
          var numerator = definitions.get(definition.numeratorMetricCode());
          var denominator = definitions.get(definition.denominatorMetricCode());
          if (numerator == null || denominator == null) continue;
          var numeratorAgg =
              bucketFacts.get(bucketKey(bucketStart, numerator.trafficSourceName(), numerator.metricCode()));
          var denominatorAgg =
              bucketFacts.get(bucketKey(bucketStart, denominator.trafficSourceName(), denominator.metricCode()));
          BigDecimal value = null;
          String availability = "NO_DATA";
          String note = NO_DATA_NOTE;
          if (numeratorAgg != null && denominatorAgg != null) {
            if (denominatorAgg.quantity().signum() == 0) {
              availability = "NOT_COMPARABLE";
              note = NOT_COMPARABLE_NOTE;
            } else {
              value =
                  numeratorAgg
                      .quantity()
                      .divide(denominatorAgg.quantity(), PERCENT_SCALE, RoundingMode.HALF_UP);
              availability = "AVAILABLE";
              note = "";
            }
          }
          metrics.add(
              new ReportingTrafficConversionModels.TrafficMetricValue(
                  definition.metricCode(),
                  definition.displayName(),
                  definition.unit().name(),
                  definition.caliber().name(),
                  null,
                  "",
                  value,
                  availability,
                  note));
        }
      }
      out.add(
          new ReportingTrafficConversionModels.TrafficTrendPoint(
              bucketStart.toString(),
              bucketEnd.toString(),
              granularity.bucketLabel(bucketStart, bucketEnd, null),
              List.copyOf(metrics)));
    }
    return List.copyOf(out);
  }

  // ==========================================================================
  // 异常诊断
  // ==========================================================================

  /** 异常诊断（**桶级环比 + 窗口级口径背离**）。 */
  private List<ReportingTrafficConversionModels.TrafficDiagnosis> diagnose(
      Map<String, ReportingTrafficConversionCatalog.Definition> definitions,
      Map<String, Agg> facts,
      Map<String, Agg> bucketFacts,
      ReportingSalesTrendGranularity granularity,
      LocalDate from,
      LocalDate to) {
    var out = new ArrayList<ReportingTrafficConversionModels.TrafficDiagnosis>();
    var buckets = new ArrayList<LocalDate>();
    var start = granularity.alignStart(from);
    var end = granularity.alignEnd(to);
    for (var cursor = start; !cursor.isAfter(end); cursor = nextBucket(cursor, granularity)) {
      buckets.add(cursor);
    }

    for (var index = 0; index < buckets.size(); index++) {
      var bucketStart = buckets.get(index);
      var bucketEnd = granularity.alignEnd(bucketStart);
      // 🔴 环比基线是**窗口内的上一个桶**：窗口外的事实没有投影 ⇒ 第一个桶没有基线，不诊断。
      if (index == 0) continue;
      var previousBucket = buckets.get(index - 1);

      var impressions = quantityOf(bucketFacts, bucketStart, "LISTING", ReportingTrafficConversionCatalog.IMPRESSIONS);
      var previousImpressions =
          quantityOf(bucketFacts, previousBucket, "LISTING", ReportingTrafficConversionCatalog.IMPRESSIONS);
      if (impressions != null && previousImpressions != null && previousImpressions.signum() != 0) {
        var changeRate =
            impressions
                .subtract(previousImpressions)
                .divide(previousImpressions, PERCENT_SCALE, RoundingMode.HALF_UP);
        if (changeRate.compareTo(ReportingTrafficConversionCatalog.IMPRESSION_COLLAPSE_THRESHOLD.negate()) < 0) {
          out.add(
              new ReportingTrafficConversionModels.TrafficDiagnosis(
                  ReportingTrafficConversionCatalog.RULE_IMPRESSION_COLLAPSE,
                  "HIGH",
                  ReportingTrafficConversionCatalog.IMPRESSIONS,
                  "BUCKET",
                  bucketStart.toString(),
                  bucketEnd.toString(),
                  impressions,
                  previousImpressions,
                  changeRate,
                  ReportingTrafficConversionCatalog.IMPRESSION_COLLAPSE_THRESHOLD,
                  "曝光从上一"
                      + granularity.label()
                      + "的 "
                      + previousImpressions.toPlainString()
                      + " 掉到 "
                      + impressions.toPlainString()
                      + "（环比 "
                      + changeRate.multiply(new BigDecimal("100")).toPlainString()
                      + "%）。",
                  "先查平台侧流量断崖：下架 / 断货 / 活动结束 / 店铺被限流。"
                      + "⚠️ 曝光是**平台口径**，与内部订单无关。"));
        }
      }

      var ctr = ratioOf(bucketFacts, bucketStart, definitions, ReportingTrafficConversionCatalog.CTR);
      var previousCtr =
          ratioOf(bucketFacts, previousBucket, definitions, ReportingTrafficConversionCatalog.CTR);
      if (ctr != null && previousCtr != null && previousCtr.signum() != 0) {
        var changeRate = ctr.subtract(previousCtr).divide(previousCtr, PERCENT_SCALE, RoundingMode.HALF_UP);
        if (changeRate.compareTo(ReportingTrafficConversionCatalog.CTR_DROP_THRESHOLD.negate()) < 0) {
          out.add(
              new ReportingTrafficConversionModels.TrafficDiagnosis(
                  ReportingTrafficConversionCatalog.RULE_CTR_DROP,
                  "MEDIUM",
                  ReportingTrafficConversionCatalog.CTR,
                  "BUCKET",
                  bucketStart.toString(),
                  bucketEnd.toString(),
                  ctr,
                  previousCtr,
                  changeRate,
                  ReportingTrafficConversionCatalog.CTR_DROP_THRESHOLD,
                  "点击率从上一"
                      + granularity.label()
                      + "的 "
                      + previousCtr.toPlainString()
                      + " 掉到 "
                      + ctr.toPlainString()
                      + "（环比 "
                      + changeRate.multiply(new BigDecimal("100")).toPlainString()
                      + "%）。",
                  "曝光还在但点击率掉了 ⇒ 看主图 / 标题 / 价格 / 排名位置，不是流量断崖。"));
        }
      }

      var conversion =
          ratioOf(bucketFacts, bucketStart, definitions, ReportingTrafficConversionCatalog.CONVERSION_RATE);
      var previousConversion =
          ratioOf(bucketFacts, previousBucket, definitions, ReportingTrafficConversionCatalog.CONVERSION_RATE);
      if (conversion != null && previousConversion != null && previousConversion.signum() != 0) {
        var changeRate =
            conversion
                .subtract(previousConversion)
                .divide(previousConversion, PERCENT_SCALE, RoundingMode.HALF_UP);
        if (changeRate.compareTo(ReportingTrafficConversionCatalog.CONVERSION_RATE_DROP_THRESHOLD.negate()) < 0) {
          out.add(
              new ReportingTrafficConversionModels.TrafficDiagnosis(
                  ReportingTrafficConversionCatalog.RULE_CONVERSION_RATE_DROP,
                  "MEDIUM",
                  ReportingTrafficConversionCatalog.CONVERSION_RATE,
                  "BUCKET",
                  bucketStart.toString(),
                  bucketEnd.toString(),
                  conversion,
                  previousConversion,
                  changeRate,
                  ReportingTrafficConversionCatalog.CONVERSION_RATE_DROP_THRESHOLD,
                  "转化率（成交件数 ÷ Session，**平台口径**）从上一"
                      + granularity.label()
                      + "的 "
                      + previousConversion.toPlainString()
                      + " 掉到 "
                      + conversion.toPlainString()
                      + "（环比 "
                      + changeRate.multiply(new BigDecimal("100")).toPlainString()
                      + "%）。",
                  "流量与点击都正常但转化掉了 ⇒ 看详情页 / 价格 / 库存 / 评价，"
                      + "或用下钻看是哪几个 listing 掉的。"));
        }
      }

      // 口径冲突：同一统计日内「点击数 > Session 数」⇒ 平台的两套口径不一致。
      var clicks = quantityOf(bucketFacts, bucketStart, "LISTING", ReportingTrafficConversionCatalog.CLICKS);
      var sessions = quantityOf(bucketFacts, bucketStart, "LISTING", ReportingTrafficConversionCatalog.SESSIONS);
      if (clicks != null && sessions != null && clicks.compareTo(sessions) > 0) {
        out.add(
            new ReportingTrafficConversionModels.TrafficDiagnosis(
                ReportingTrafficConversionCatalog.RULE_SESSION_CLICK_GAP,
                "HIGH",
                ReportingTrafficConversionCatalog.SESSIONS,
                "BUCKET",
                bucketStart.toString(),
                bucketEnd.toString(),
                clicks,
                sessions,
                null,
                null,
                "这个"
                    + granularity.label()
                    + "的点击数（"
                    + clicks.toPlainString()
                    + "）大于 Session 数（"
                    + sessions.toPlainString()
                    + "）⇒ 平台这两项的统计范围不一致（或 Session 没回全）。",
                "🔴 这是**口径异常**，不是流量异常：拿这格算转化率会低估。"
                    + "先核对平台报表的回传范围，再决定要不要用这一格。"));
      }
    }

    // ---- 窗口级：平台口径成交件数 vs 订单口径订单笔数（**口径背离**）----
    var platformUnits =
        quantityOf(facts, null, "LISTING", ReportingTrafficConversionCatalog.PLATFORM_UNITS);
    var orderCount = quantityOf(facts, null, "ORDER", ReportingTrafficConversionCatalog.ORDER_COUNT);
    if (platformUnits != null && orderCount != null) {
      var larger = platformUnits.max(orderCount);
      if (larger.signum() != 0) {
        var deviation =
            platformUnits.subtract(orderCount).abs().divide(larger, PERCENT_SCALE, RoundingMode.HALF_UP);
        if (deviation.compareTo(ReportingTrafficConversionCatalog.CALIBER_GAP_THRESHOLD) > 0) {
          out.add(
              new ReportingTrafficConversionModels.TrafficDiagnosis(
                  ReportingTrafficConversionCatalog.RULE_CALIBER_GAP,
                  "MEDIUM",
                  ReportingTrafficConversionCatalog.PLATFORM_UNITS,
                  "WINDOW",
                  from.toString(),
                  to.toString(),
                  platformUnits,
                  orderCount,
                  deviation,
                  ReportingTrafficConversionCatalog.CALIBER_GAP_THRESHOLD,
                  "平台口径成交件数（"
                      + platformUnits.toPlainString()
                      + "）与订单口径订单笔数（"
                      + orderCount.toPlainString()
                      + "）偏差 "
                      + deviation.multiply(new BigDecimal("100")).toPlainString()
                      + "%。",
                  "🔴 这是**两套口径统计范围不同**（平台自然日 / 含取消与部分退款 / 平台单回挂规则），"
                      + "**不是数据错** —— 但两数不能互相替代，也不能相加。"
                      + "需要逐单核对时用下钻（订单口径的行有下单时刻）。"));
        }
      }
    }
    return List.copyOf(out);
  }

  // ==========================================================================
  // 下钻
  // ==========================================================================

  /**
   * 构成明细下钻：某个来源、某个指标在窗口内的构成明细。
   *
   * @param trafficSource LISTING / AD / ORDER（空 = 全部）
   * @param metricCode 指标码（空 = 全部）
   */
  public ReportingTrafficConversionModels.TrafficDrilldownResponse drilldown(
      UUID tenantId,
      LocalDate from,
      LocalDate to,
      String trafficSource,
      String metricCode,
      int page,
      int size) {
    return tenants.run(
        tenantId,
        () -> {
          var source = trafficSource == null || trafficSource.isBlank() ? null : trafficSource.trim();
          var metric = metricCode == null || metricCode.isBlank() ? null : metricCode.trim();
          var safePage = Math.max(page, 0);
          var safeSize = size <= 0 ? 50 : Math.min(size, 200);

          var total =
              sql.fetch(
                      """
                      select count(*) from reporting_traffic_conversion_drilldown_fact
                       where tenant_id = ? and stat_date >= ? and stat_date <= ?
                         and (?::text is null or traffic_source = ?::text)
                         and (?::text is null or metric_code = ?::text)
                      """,
                      tenantId,
                      from,
                      to,
                      source,
                      source,
                      metric,
                      metric)
                  .stream()
                  .findFirst()
                  .map(r -> r.get(0, Long.class))
                  .orElse(0L);

          var rows =
              sql.fetch(
                  """
                  select traffic_source, source_kind, reference_id, reference_label, shop_code,
                         platform_code, order_no, occurred_at, stat_date, currency, quantity, value
                    from reporting_traffic_conversion_drilldown_fact
                   where tenant_id = ? and stat_date >= ? and stat_date <= ?
                     and (?::text is null or traffic_source = ?::text)
                     and (?::text is null or metric_code = ?::text)
                   order by stat_date desc, value desc, quantity desc, reference_id asc
                   limit ? offset ?
                  """,
                  tenantId,
                  from,
                  to,
                  source,
                  source,
                  metric,
                  metric,
                  safeSize,
                  safePage * safeSize);

          var items = new ArrayList<ReportingTrafficConversionModels.TrafficDrilldownRow>();
          for (var row : rows) {
            items.add(
                new ReportingTrafficConversionModels.TrafficDrilldownRow(
                    row.get(0, String.class),
                    row.get(1, String.class),
                    row.get(2, String.class),
                    row.get(3, String.class),
                    row.get(4, String.class),
                    row.get(5, String.class),
                    row.get(6, String.class),
                    row.get(7, OffsetDateTime.class),
                    row.get(8, LocalDate.class) == null ? "" : row.get(8, LocalDate.class).toString(),
                    row.get(9, String.class),
                    nz(row.get(10, BigDecimal.class)),
                    nz(row.get(11, BigDecimal.class))));
          }

          var updatedAt = dataUpdatedAt(tenantId);
          var empty = items.isEmpty();
          return new ReportingTrafficConversionModels.TrafficDrilldownResponse(
              from,
              to,
              source == null ? "" : source,
              metric == null ? "" : metric,
              total,
              safePage,
              safeSize,
              List.copyOf(items),
              updatedAt,
              updatedAt == null
                  ? "本租户还没有任何流量转化投影数据（空表时**不显示**假更新时间）。"
                  : "数据更新时间 = 最近一次投影落库时刻。",
              empty ? "EMPTY" : "OK",
              empty ? "这个窗口内没有构成明细。" : "已按筛选条件给出构成明细。",
              empty ? "NO_FACTS_IN_WINDOW" : null,
              empty
                  ? "这个窗口内没有匹配筛选条件的构成明细 —— 可能是没数据，"
                      + "也可能是筛选条件太窄。"
                  : "",
              List.of());
        });
  }

  // ==========================================================================
  // 取数
  // ==========================================================================

  /** 窗口内的事实（来源 × 指标 × 币种 → 聚合值）。 */
  private Map<String, Agg> loadFacts(UUID tenantId, LocalDate from, LocalDate to) {
    var rows =
        sql.fetch(
            """
            select traffic_source, metric_code, currency, sum(value) v, sum(quantity) q
              from reporting_traffic_conversion_fact
             where tenant_id = ? and stat_date >= ? and stat_date <= ?
             group by traffic_source, metric_code, currency
            """,
            tenantId,
            from,
            to);
    var out = new LinkedHashMap<String, Agg>();
    for (var row : rows) {
      out.put(
          key(row.get(0, String.class), row.get(1, String.class), row.get(2, String.class)),
          new Agg(nz(row.get(3, BigDecimal.class)), nz(row.get(4, BigDecimal.class))));
    }
    return out;
  }

  /** 按桶归集的事实（**桶的对齐在 SQL 里做**，与 {@code ReportingSalesTrendGranularity} 同构）。 */
  private Map<String, Agg> loadBucketFacts(
      UUID tenantId, LocalDate from, LocalDate to, ReportingSalesTrendGranularity granularity) {
    var rows =
        sql.fetch(
            """
            select date_trunc(?, stat_date) bucket, traffic_source, metric_code,
                   sum(value) v, sum(quantity) q
              from reporting_traffic_conversion_fact
             where tenant_id = ? and stat_date >= ? and stat_date <= ?
               and metric_code in (?,?,?,?,?,?,?,?,?,?,?)
             group by bucket, traffic_source, metric_code
            """,
            granularity.sqlUnit(),
            tenantId,
            from,
            to,
            ReportingTrafficConversionCatalog.IMPRESSIONS,
            ReportingTrafficConversionCatalog.CLICKS,
            ReportingTrafficConversionCatalog.SESSIONS,
            ReportingTrafficConversionCatalog.PLATFORM_UNITS,
            ReportingTrafficConversionCatalog.CTR,
            ReportingTrafficConversionCatalog.CONVERSION_RATE,
            ReportingTrafficConversionCatalog.AD_IMPRESSIONS,
            ReportingTrafficConversionCatalog.AD_CLICKS,
            ReportingTrafficConversionCatalog.AD_ORDERS,
            ReportingTrafficConversionCatalog.AD_CONVERSION_RATE,
            ReportingTrafficConversionCatalog.ORDER_COUNT);
    var out = new LinkedHashMap<String, Agg>();
    for (var row : rows) {
      var bucket = row.get(0, LocalDate.class);
      if (bucket == null) continue;
      out.put(
          bucketKey(bucket, row.get(1, String.class), row.get(2, String.class)),
          new Agg(nz(row.get(3, BigDecimal.class)), nz(row.get(4, BigDecimal.class))));
    }
    return out;
  }

  /** 读口径表（**运行时唯一的真相**）。 */
  private Map<String, ReportingTrafficConversionCatalog.Definition> definitionMap(UUID tenantId) {
    var rows =
        sql.fetch(
            """
            select metric_code, display_name, unit, aggregation, numerator_metric_code,
                   denominator_metric_code, caliber, traffic_source, currency_aware, description,
                   owner_module
              from reporting_traffic_conversion_metric_definition
             where tenant_id = ? and enabled
             order by metric_code
            """,
            tenantId);
    var out = new LinkedHashMap<String, ReportingTrafficConversionCatalog.Definition>();
    for (var row : rows) {
      var code = row.get(0, String.class);
      out.put(
          code,
          new ReportingTrafficConversionCatalog.Definition(
              code,
              row.get(1, String.class),
              ReportingTrafficConversionCatalog.Unit.valueOf(row.get(2, String.class)),
              ReportingTrafficConversionCatalog.Aggregation.valueOf(row.get(3, String.class)),
              row.get(4, String.class),
              row.get(5, String.class),
              ReportingTrafficConversionCatalog.Caliber.valueOf(row.get(6, String.class)),
              row.get(7, String.class) == null
                  ? null
                  : ReportingTrafficConversionCatalog.TrafficSource.valueOf(row.get(7, String.class)),
              row.get(8, Boolean.class),
              row.get(9, String.class),
              row.get(10, String.class)));
    }
    return out;
  }

  /** 口径目录的展示形态。 */
  private List<ReportingTrafficConversionModels.TrafficMetricDefinitionView> definitionViews(
      Map<String, ReportingTrafficConversionCatalog.Definition> definitions) {
    var out = new ArrayList<ReportingTrafficConversionModels.TrafficMetricDefinitionView>();
    for (var definition : definitions.values()) {
      out.add(
          new ReportingTrafficConversionModels.TrafficMetricDefinitionView(
              definition.metricCode(),
              definition.displayName(),
              definition.unit().name(),
              definition.aggregation().name(),
              definition.numeratorMetricCode(),
              definition.denominatorMetricCode(),
              definition.caliber().name(),
              definition.trafficSourceName(),
              definition.currencyAware(),
              definition.description(),
              definition.ownerModule()));
    }
    return List.copyOf(out);
  }

  /** 数据更新时间（**空表时是 null**，不是假时间）。 */
  private OffsetDateTime dataUpdatedAt(UUID tenantId) {
    var row =
        sql.fetchOne(
            "select max(updated_at) from reporting_traffic_conversion_fact where tenant_id = ?",
            tenantId);
    return row == null ? null : row.get(0, OffsetDateTime.class);
  }

  // ==========================================================================
  // 小工具
  // ==========================================================================

  /** 一个聚合值（金额 + 数量）。 */
  private record Agg(BigDecimal value, BigDecimal quantity) {}

  /** 事实桶的键。 */
  private static String key(String trafficSource, String metricCode, String currency) {
    return trafficSource + "|" + metricCode + "|" + currency;
  }

  /** 趋势桶的键（**与货币无关的指标**才进这张表）。 */
  private static String bucketKey(LocalDate bucket, String trafficSource, String metricCode) {
    return bucket + "|" + trafficSource + "|" + metricCode;
  }

  /** 取一个数量型指标的值（bucket 为 null = 窗口合计，**跨币种合计**）。 */
  private static BigDecimal quantityOf(
      Map<String, Agg> facts, LocalDate bucket, String trafficSource, String metricCode) {
    if (bucket == null) return crossTotal(facts, trafficSource, metricCode);
    var agg = facts.get(bucketKey(bucket, trafficSource, metricCode));
    return agg == null ? null : agg.quantity();
  }

  /** 取一个比率型指标的值（bucket 为 null = 窗口合计）。 */
  private static BigDecimal ratioOf(
      Map<String, Agg> facts,
      LocalDate bucket,
      Map<String, ReportingTrafficConversionCatalog.Definition> definitions,
      String metricCode) {
    var definition = definitions.get(metricCode);
    if (definition == null) return null;
    var numerator = definitions.get(definition.numeratorMetricCode());
    var denominator = definitions.get(definition.denominatorMetricCode());
    if (numerator == null || denominator == null) return null;
    var numeratorValue = quantityOf(facts, bucket, numerator.trafficSourceName(), numerator.metricCode());
    var denominatorValue = quantityOf(facts, bucket, denominator.trafficSourceName(), denominator.metricCode());
    if (numeratorValue == null || denominatorValue == null) return null;
    if (denominatorValue.signum() == 0) return null;
    return numeratorValue.divide(denominatorValue, PERCENT_SCALE, RoundingMode.HALF_UP);
  }

  /**
   * 取一个指标在**某个币种桶**里的原始值（金额型取 value，数量型取 quantity）。
   *
   * <p>🔴 「金额除以量，必须同币种」⇒ 客单价的分子分母都走这里（同一个币种桶）。
   */
  private static BigDecimal bucketValue(
      Map<String, Agg> facts,
      ReportingTrafficConversionCatalog.Definition definition,
      String currency) {
    var agg = facts.get(key(definition.trafficSourceName(), definition.metricCode(), currency));
    if (agg == null) return null;
    return definition.unit() == ReportingTrafficConversionCatalog.Unit.CURRENCY
        ? agg.value()
        : agg.quantity();
  }

  /**
   * 与货币无关的指标的取值：**跨币种合计**（件数 / 单数相加不会把两种货币的钱加起来）。
   *
   * <p>⚠️ 事实仍**按币种分行存储**（与 13.2.2.2 同纪律）⇒ 派生指标（客单价）能按同币种取数。
   */
  private static BigDecimal crossValue(
      Map<String, Agg> facts, ReportingTrafficConversionCatalog.Definition definition) {
    if (definition.unit() == ReportingTrafficConversionCatalog.Unit.CURRENCY) {
      // 涉币指标**绝不跨币种相加** ⇒ 这里只在「只有一个币种」时给出合计。
      var currencies = currenciesOf(facts, definition);
      if (currencies.size() != 1) return null;
      return bucketValue(facts, definition, currencies.iterator().next());
    }
    return crossTotal(facts, definition.trafficSourceName(), definition.metricCode());
  }

  /** 某个来源某个指标的数量**跨币种合计**（null = 窗口内没有事实）。 */
  private static BigDecimal crossTotal(
      Map<String, Agg> facts, String trafficSource, String metricCode) {
    var prefix = trafficSource + "|" + metricCode + "|";
    BigDecimal total = BigDecimal.ZERO;
    var found = false;
    for (var entry : facts.entrySet()) {
      if (!entry.getKey().startsWith(prefix)) continue;
      var agg = entry.getValue();
      total = total.add(agg.quantity());
      found = true;
    }
    return found ? total : null;
  }

  /** 某个指标出现过的币种（**按币种分别报，绝不跨币种相加**）。 */
  private static Set<String> currenciesOf(
      Map<String, Agg> facts, ReportingTrafficConversionCatalog.Definition definition) {
    var out = new TreeSet<String>();
    var prefix = definition.trafficSourceName() + "|" + definition.metricCode() + "|";
    for (var entry : facts.keySet()) {
      if (!entry.startsWith(prefix)) continue;
      var currency = entry.substring(prefix.length());
      if (currency.isEmpty()) continue;
      out.add(currency);
    }
    return out;
  }

  /** 窗口里出现过哪些来源。 */
  private static Set<String> presentSources(Map<String, Agg> facts) {
    var out = new LinkedHashSet<String>();
    for (var entry : facts.keySet()) {
      out.add(entry.substring(0, entry.indexOf('|')));
    }
    return out;
  }

  /** 下一个桶（**与 {@code ReportingSalesTrendGranularity} 同构**）。 */
  private static LocalDate nextBucket(
      LocalDate bucketStart, ReportingSalesTrendGranularity granularity) {
    return switch (granularity) {
      case DAY, HOUR -> bucketStart.plusDays(1);
      case WEEK -> bucketStart.plusWeeks(1);
      case MONTH -> bucketStart.plusMonths(1);
    };
  }

  private static BigDecimal nz(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }
}
