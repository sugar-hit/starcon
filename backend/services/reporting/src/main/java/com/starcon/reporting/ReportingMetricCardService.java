package com.starcon.reporting;

import com.starcon.tenancy.TenantTransactions;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 1.1.2.1 核心指标卡的**读模型**（蓝图 01 卷「输入」句：reporting 服务的聚合投影 + 用户数据范围
 * + 时间口径选择）。
 *
 * <p>🔴 **本类不重算口径**：数值与口径全部来自
 * {@code reporting_metric_definition} + {@code reporting_metric_fact}
 * （经由 {@link ReportingOverviewService} 求值），派生型指标（毛利）是**按口径表的分子分母
 * 递归求值**出来的，而不是在这里写死一句「销售额 − 成本」。
 *
 * <p>🔴 **回款是唯一不走投影的一张卡**：它的口径归 12 财务（12.2.1.1 收款单），
 * 本服务只**引用上游自己算好的合计**（{@code GET /finance/receipts} 的按币种汇总），
 * **不把收款单拉回来自己加** —— 那就是在跨服务重算口径。
 *
 * <p>🔴 **在途金额按「未接通」显示**：口径表里没有这个指标码，上游在途事实只给数量不给金额，
 * 把数量乘一个成本价就是**在代码里新写一条指标公式**（会出现第二个真相）。
 * 所以它只报 {@code available=false} + 归属模块，**不给公式也不给分母**。
 *
 * <p>🔴 **三个「判不了」分开说**：
 *
 * <ul>
 *   <li>基期没数据 ⇒ 变化率 {@code null}（不是 0%、不是「持平」）；</li>
 *   <li>多币种没折算 ⇒ 不可判定（**没有汇率就不可判定，不是按 1:1 算**）；</li>
 *   <li>分母为 0 ⇒ 派生比算不出（不是 0%）。</li>
 * </ul>
 */
@Service
public class ReportingMetricCardService {

  /** 时间口径选择。 */
  public static final String WINDOW_TODAY = "TODAY";
  public static final String WINDOW_YESTERDAY = "YESTERDAY";
  public static final String WINDOW_THIS_MONTH = "THIS_MONTH";
  public static final String WINDOW_CUSTOM = "CUSTOM";

  /** 卡片状态。 */
  public static final String STATUS_AVAILABLE = "AVAILABLE";
  public static final String STATUS_NO_DATA = "NO_DATA";
  public static final String STATUS_NOT_CONNECTED = "NOT_CONNECTED";
  public static final String STATUS_UPSTREAM_UNAVAILABLE = "UPSTREAM_UNAVAILABLE";

  /** 回款只认「已登记」的收款单（作废单不是到账）。 */
  private static final String RECEIPT_STATUS = "RECORDED";

  private final ReportingOverviewService overview;
  private final ReportingFinancePort finance;
  private final DSLContext sql;
  private final TenantTransactions tenants;
  private final Clock clock;

  public ReportingMetricCardService(
      ReportingOverviewService overview,
      ReportingFinancePort finance,
      DSLContext sql,
      TenantTransactions tenants,
      Clock clock) {
    this.overview = overview;
    this.finance = finance;
    this.sql = sql;
    this.tenants = tenants;
    this.clock = clock;
  }

  // ==========================================================================
  // 能力声明
  // ==========================================================================

  /** 逐条报出每个指标的上游、口径、归属与下钻目标。 */
  public ReportingMetricCardModels.MetricCardsCapabilities capabilities(UUID tenantId) {
    return tenants.run(
        tenantId,
        () -> {
          var definitions = new LinkedHashMap<String, ReportingOverviewModels.MetricDefinitionView>();
          for (var definition : overview.metricDefinitions(tenantId)) {
            definitions.put(definition.metricCode(), definition);
          }
          var out = new ArrayList<ReportingMetricCardModels.MetricCardCapability>();
          for (var spec : ReportingMetricCardCatalog.CARDS) {
            out.add(capability(spec, definitions.get(spec.metricCode())));
          }
          return new ReportingMetricCardModels.MetricCardsCapabilities(
              true,
              true,
              true,
              out,
              UPSTREAM_MODULES,
              NOT_DELIVERED,
              NOTES);
        });
  }

  /** 一条指标的能力声明。 */
  private ReportingMetricCardModels.MetricCardCapability capability(
      ReportingMetricCardCatalog.CardSpec spec, ReportingOverviewModels.MetricDefinitionView definition) {
    if (spec.source() == ReportingMetricCardCatalog.Source.NOT_CONNECTED) {
      return new ReportingMetricCardModels.MetricCardCapability(
          spec.key(),
          spec.metricCode(),
          spec.displayName(),
          spec.unit(),
          spec.currencyAware(),
          false,
          spec.ownerModule(),
          "",
          "",
          null,
          null,
          "",
          spec.source().name(),
          false,
          null,
          spec.unavailableReason(),
          "未接通的指标**不给公式也不给分母** —— 给了就是编一个出来。");
    }
    if (spec.source() == ReportingMetricCardCatalog.Source.AUTHORIZED_API) {
      return new ReportingMetricCardModels.MetricCardCapability(
          spec.key(),
          spec.metricCode(),
          spec.displayName(),
          spec.unit(),
          spec.currencyAware(),
          true,
          spec.ownerModule(),
          spec.caliberHint(),
          "UPSTREAM_SUMMARY",
          null,
          null,
          spec.sourceTables(),
          spec.source().name(),
          spec.reportPath() != null,
          spec.reportName(),
          "",
          "该指标码**不在**本服务的口径表里：口径归归属系统，本模块只引用上游算好的合计。");
    }
    // 投影型：口径全部来自口径表（运行时唯一真相）。
    if (definition == null) {
      return new ReportingMetricCardModels.MetricCardCapability(
          spec.key(),
          spec.metricCode(),
          spec.displayName(),
          spec.unit(),
          spec.currencyAware(),
          false,
          spec.ownerModule(),
          "",
          "",
          null,
          null,
          spec.sourceTables(),
          spec.source().name(),
          false,
          null,
          "该指标码未在口径表（reporting_metric_definition）登记 —— 口径唯一，没有登记就没有这个指标。",
          "口径表的登记在 13.1.1.1 经营总览里维护。");
    }
    return new ReportingMetricCardModels.MetricCardCapability(
        spec.key(),
        spec.metricCode(),
        spec.displayName(),
        spec.unit(),
        definition.currencyAware(),
        true,
        definition.ownerModule(),
        definition.description(),
        definition.aggregation(),
        definition.numeratorMetricCode(),
        definition.denominatorMetricCode(),
        spec.sourceTables(),
        spec.source().name(),
        spec.reportPath() != null,
        spec.reportName(),
        "",
        "口径取自 reporting_metric_definition（运行时唯一真相），本模块不复制一份。");
  }

  // ==========================================================================
  // 卡片数据
  // ==========================================================================

  /**
   * 一次取数：时间窗口 → 各指标值 + 环比 + 同比 + 趋势箭头 + 下钻链接 + 口径说明 + 数据截至时刻。
   *
   * @param preset 时间口径：TODAY / YESTERDAY / THIS_MONTH / CUSTOM
   * @param from 自定义窗口的起（CUSTOM 时必填）
   * @param to 自定义窗口的止（CUSTOM 时必填）
   * @param baseCurrency 本位币（可空 ⇒ 只按币种分别报，不做跨币种合计）
   */
  public ReportingMetricCardModels.MetricCardsResponse cards(
      UUID tenantId, String bearer, String preset, LocalDate from, LocalDate to, String baseCurrency) {
    return tenants.run(
        tenantId,
        () -> {
          var window = resolveWindow(tenantId, preset, from, to);
          var base = baseCurrency == null || baseCurrency.isBlank() ? null : baseCurrency.trim().toUpperCase();
          var response =
              overview.overview(tenantId, window.from(), window.to(), "COMPANY", null, null, base);
          var byCode = new LinkedHashMap<String, ReportingOverviewModels.MetricCard>();
          for (var card : response.cards()) {
            byCode.put(card.metricCode(), card);
          }

          var cards = new ArrayList<ReportingMetricCardModels.MetricCardEntry>();
          for (var spec : ReportingMetricCardCatalog.CARDS) {
            cards.add(card(spec, byCode.get(spec.metricCode()), tenantId, bearer, window, base));
          }

          var warnings = new ArrayList<>(response.warnings());
          if (window.note() != null && !window.note().isBlank()) warnings.add(window.note());
          if (!"UTC".equals(window.timezone())) {
            warnings.add(
                "「今日 / 昨日 / 本月」的边界按本租户时区 "
                    + window.timezone()
                    + " 计算（不是写死 UTC）；窗口涉及的分桶时区："
                    + String.join("、", response.timezones())
                    + "。");
          } else if ("FALLBACK_UTC".equals(window.timezoneSource())) {
            warnings.add(
                "本租户还没有任何投影事实，取不到自己的分桶时区，窗口边界暂按 UTC 计算 —— "
                    + "一旦投影进来就会改用该租户自己的时区。");
          }

          return new ReportingMetricCardModels.MetricCardsResponse(
              window.preset(),
              window.from(),
              window.to(),
              window.asOfDate(),
              window.timezone(),
              window.timezoneSource(),
              response.timezones(),
              base,
              cards,
              response.dataUpdatedAt(),
              response.dataUpdatedNote(),
              warnings,
              response.currencyNote());
        });
  }

  /** 一张卡。 */
  private ReportingMetricCardModels.MetricCardEntry card(
      ReportingMetricCardCatalog.CardSpec spec,
      ReportingOverviewModels.MetricCard source,
      UUID tenantId,
      String bearer,
      Window window,
      String baseCurrency) {
    return switch (spec.source()) {
      case REPORTING_PROJECTION -> projectionCard(spec, source, tenantId, window, baseCurrency);
      case AUTHORIZED_API -> cashReceivedCard(spec, tenantId, bearer, window, baseCurrency);
      case NOT_CONNECTED -> notConnectedCard(spec);
    };
  }

  /** 投影型卡片（口径与数值全部来自口径表 + 投影事实）。 */
  private ReportingMetricCardModels.MetricCardEntry projectionCard(
      ReportingMetricCardCatalog.CardSpec spec,
      ReportingOverviewModels.MetricCard source,
      UUID tenantId,
      Window window,
      String baseCurrency) {
    if (source == null) {
      return new ReportingMetricCardModels.MetricCardEntry(
          spec.key(),
          spec.metricCode(),
          spec.displayName(),
          spec.unit(),
          spec.currencyAware(),
          STATUS_NOT_CONNECTED,
          List.of(),
          null,
          null,
          null,
          "UNKNOWN",
          "无",
          "",
          spec.ownerModule(),
          notConnectedDrilldown(spec, "该指标码未在口径表登记，没有可引用的口径。"),
          "口径表里没有这个指标码 —— 口径唯一，没有登记就没有这个指标（不显示成 0）。");
    }
    var values = new ArrayList<ReportingMetricCardModels.MetricCardAmount>();
    for (var value : source.values()) {
      values.add(new ReportingMetricCardModels.MetricCardAmount(value.currency(), value.value()));
    }
    var headline = headline(values, source.baseTotal(), source.baseCurrency());
    var previous =
        comparison(
            source.previousPeriod(),
            source.baseTotal(),
            baseCurrency,
            window.to(),
            tenantId,
            spec.currencyAware());
    var yearOverYear =
        comparison(
            source.yearOverYear(),
            source.baseTotal(),
            baseCurrency,
            window.to(),
            tenantId,
            spec.currencyAware());
    var trend = trend(previous, yearOverYear);
    var status = values.isEmpty() ? STATUS_NO_DATA : STATUS_AVAILABLE;
    var note =
        values.isEmpty()
            ? "该口径下**没有事实数据**（不是 0）：上游还没有投影，或这个窗口里没有这类事实。"
            : "";
    return new ReportingMetricCardModels.MetricCardEntry(
        spec.key(),
        spec.metricCode(),
        spec.displayName(),
        spec.unit(),
        spec.currencyAware(),
        status,
        values,
        headline,
        previous,
        yearOverYear,
        trend.direction(),
        trend.basis(),
        source.description(),
        source.ownerModule(),
        drilldown(spec, tenantId, window),
        note);
  }

  /**
   * 回款卡片（口径归 12.2.1.1 收款单）。
   *
   * <p>⚠️ 取的是**上游自己算好的按币种汇总**（{@code summaryByCurrency}），不是把收款单拉回来自己加。
   */
  private ReportingMetricCardModels.MetricCardEntry cashReceivedCard(
      ReportingMetricCardCatalog.CardSpec spec,
      UUID tenantId,
      String bearer,
      Window window,
      String baseCurrency) {
    var previousWindow = previousWindow(window.from(), window.to());
    var yearAgoWindow = yearAgoWindow(window.from(), window.to());
    ReportingFinancePort.ReceiptPage current;
    ReportingFinancePort.ReceiptPage previous;
    ReportingFinancePort.ReceiptPage yearAgo;
    try {
      current = finance.receipts(window.from(), window.to(), RECEIPT_STATUS, 0, bearer);
      previous = finance.receipts(previousWindow.from(), previousWindow.to(), RECEIPT_STATUS, 0, bearer);
      yearAgo = finance.receipts(yearAgoWindow.from(), yearAgoWindow.to(), RECEIPT_STATUS, 0, bearer);
    } catch (RuntimeException e) {
      return new ReportingMetricCardModels.MetricCardEntry(
          spec.key(),
          spec.metricCode(),
          spec.displayName(),
          spec.unit(),
          spec.currencyAware(),
          STATUS_UPSTREAM_UNAVAILABLE,
          List.of(),
          null,
          null,
          null,
          "UNKNOWN",
          "无",
          spec.caliberHint(),
          spec.ownerModule(),
          drilldown(spec, tenantId, window),
          "上游 12.2.1.1 收款单本次取不到（没有 finance.receipt.read 权限，或财务服务不可达）"
              + " —— 这是「读不到」，不是「回款为 0」。");
    }
    var values = receiptAmounts(current);
    var previousValues = receiptAmounts(previous);
    var yearAgoValues = receiptAmounts(yearAgo);
    var currentBase = convertToBase(values, baseCurrency, window.to(), tenantId);
    var previousBase = convertToBase(previousValues, baseCurrency, window.to(), tenantId);
    var yearAgoBase = convertToBase(yearAgoValues, baseCurrency, window.to(), tenantId);
    var previousComparison =
        receiptComparison(
            "环比（上一等长周期）",
            previousWindow,
            values,
            previousValues,
            currentBase,
            previousBase,
            baseCurrency);
    var yearAgoComparison =
        receiptComparison(
            "同比（去年同期）",
            yearAgoWindow,
            values,
            yearAgoValues,
            currentBase,
            yearAgoBase,
            baseCurrency);
    var trend = trend(previousComparison, yearAgoComparison);
    return new ReportingMetricCardModels.MetricCardEntry(
        spec.key(),
        spec.metricCode(),
        spec.displayName(),
        spec.unit(),
        spec.currencyAware(),
        values.isEmpty() ? STATUS_NO_DATA : STATUS_AVAILABLE,
        values,
        headline(values, currentBase, baseCurrency),
        previousComparison,
        yearAgoComparison,
        trend.direction(),
        trend.basis(),
        spec.caliberHint(),
        spec.ownerModule(),
        drilldown(spec, tenantId, window),
        values.isEmpty() ? "这个窗口里没有到账的收款单（不是回款为 0）。" : "");
  }

  /** 未接通的卡片：给归属模块，**不给公式也不给分母**。 */
  private ReportingMetricCardModels.MetricCardEntry notConnectedCard(
      ReportingMetricCardCatalog.CardSpec spec) {
    return new ReportingMetricCardModels.MetricCardEntry(
        spec.key(),
        spec.metricCode(),
        spec.displayName(),
        spec.unit(),
        spec.currencyAware(),
        STATUS_NOT_CONNECTED,
        List.of(),
        null,
        null,
        null,
        "UNKNOWN",
        "无",
        "",
        spec.ownerModule(),
        notConnectedDrilldown(spec, spec.unavailableReason()),
        spec.unavailableReason());
  }

  // ==========================================================================
  // 口径求值与对比
  // ==========================================================================

  /** 主值：单币种取该币种；多币种取本位币合计（折算不出来就没有主值，不挑一个币种充数）。 */
  private ReportingMetricCardModels.MetricCardAmount headline(
      List<ReportingMetricCardModels.MetricCardAmount> values,
      BigDecimal baseTotal,
      String baseCurrency) {
    if (values.isEmpty()) return null;
    if (values.size() == 1) return values.get(0);
    if (baseTotal != null && baseCurrency != null) {
      return new ReportingMetricCardModels.MetricCardAmount(baseCurrency, baseTotal);
    }
    return null;
  }

  /**
   * 把 13.1.1.1 的对比结果搬成本模块的形状，并在**多币种**时补一次本位币折算比较。
   *
   * <p>⚠️ 13.1.1.1 的对比在多币种时不给变化率（它不跨币种相加）；本模块按任务要求
   * 「用汇率快照折算后再比」，**折不出来就是不可判定**（不是按 1:1 算，也不是 0%）。
   */
  private ReportingMetricCardModels.MetricCardComparison comparison(
      ReportingOverviewModels.Comparison source,
      BigDecimal currentBaseTotal,
      String baseCurrency,
      LocalDate rateAsOf,
      UUID tenantId,
      boolean currencyAware) {
    if (source == null) return null;
    var changeRate = source.changeRate();
    var changeAmount = source.changeAmount();
    var reason = source.note();
    if (changeRate == null && currencyAware && baseCurrency != null && currentBaseTotal != null) {
      var previousBase =
          convertToBase(toAmounts(source.values()), baseCurrency, rateAsOf, tenantId);
      if (previousBase == null) {
        reason =
            "对比期缺少汇率快照，折算不到同一本位币 —— 不可判定（没有汇率就不可判定，不是按 1:1 算）。";
      } else if (previousBase.signum() == 0) {
        reason = "对比期折算到本位币后为 0，变化率不可比（不显示成 0%）。";
      } else {
        changeAmount = currentBaseTotal.subtract(previousBase).setScale(4, RoundingMode.HALF_UP);
        changeRate =
            changeAmount
                .divide(previousBase.abs(), 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(2, RoundingMode.HALF_UP);
        reason = "已按汇率快照折算到 " + baseCurrency + " 后比较（同一汇率基准）。";
      }
    }
    return new ReportingMetricCardModels.MetricCardComparison(
        source.label(),
        source.from(),
        source.to(),
        changeRate,
        changeAmount,
        changeRate != null,
        reason);
  }

  /** 回款的对比（口径同上：单币种直接比，多币种折算后再比，折不出来不可判定）。 */
  private ReportingMetricCardModels.MetricCardComparison receiptComparison(
      String label,
      Window window,
      List<ReportingMetricCardModels.MetricCardAmount> currentValues,
      List<ReportingMetricCardModels.MetricCardAmount> previousValues,
      BigDecimal currentBase,
      BigDecimal previousBase,
      String baseCurrency) {
    var changeRate = (BigDecimal) null;
    var changeAmount = (BigDecimal) null;
    var reason = "";
    if (currentValues.size() == 1) {
      var currency = currentValues.get(0).currency();
      var current = currentValues.get(0).value();
      var previous =
          previousValues.stream()
              .filter(v -> v.currency().equals(currency))
              .map(ReportingMetricCardModels.MetricCardAmount::value)
              .findFirst()
              .orElse(null);
      if (previous == null || current == null) {
        reason = "对比期或本期缺少这个币种的数据，无法计算变化率。";
      } else if (previous.signum() == 0) {
        reason = "对比期取值为 0，变化率不可比（不显示成 0%）。";
      } else {
        changeAmount = current.subtract(previous).setScale(4, RoundingMode.HALF_UP);
        changeRate =
            changeAmount
                .divide(previous.abs(), 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(2, RoundingMode.HALF_UP);
      }
    } else if (baseCurrency == null) {
      reason = "多币种指标没有单一的变化率，请指定本位币后按汇率快照折算比较（不跨币种相加）。";
    } else if (currentBase == null || previousBase == null) {
      reason = "缺少汇率快照，折算不到同一本位币 —— 不可判定（没有汇率就不可判定，不是按 1:1 算）。";
    } else if (previousBase.signum() == 0) {
      reason = "对比期折算到本位币后为 0，变化率不可比（不显示成 0%）。";
    } else {
      changeAmount = currentBase.subtract(previousBase).setScale(4, RoundingMode.HALF_UP);
      changeRate =
          changeAmount
              .divide(previousBase.abs(), 6, RoundingMode.HALF_UP)
              .multiply(BigDecimal.valueOf(100))
              .setScale(2, RoundingMode.HALF_UP);
      reason = "已按汇率快照折算到 " + baseCurrency + " 后比较（同一汇率基准）。";
    }
    return new ReportingMetricCardModels.MetricCardComparison(
        label, window.from(), window.to(), changeRate, changeAmount, changeRate != null, reason);
  }

  /** 趋势箭头（**由后端算出的变化率决定**，前端不许自己拿两个数做减法）。 */
  private record Trend(String direction, String basis) {}

  private Trend trend(
      ReportingMetricCardModels.MetricCardComparison previous,
      ReportingMetricCardModels.MetricCardComparison yearOverYear) {
    if (previous != null && previous.comparable() && previous.changeRate() != null) {
      return new Trend(direction(previous.changeRate()), "环比");
    }
    if (yearOverYear != null && yearOverYear.comparable() && yearOverYear.changeRate() != null) {
      return new Trend(direction(yearOverYear.changeRate()), "同比");
    }
    return new Trend("UNKNOWN", "无");
  }

  private String direction(BigDecimal changeRate) {
    var sign = changeRate.signum();
    if (sign > 0) return "UP";
    if (sign < 0) return "DOWN";
    return "FLAT";
  }

  // ==========================================================================
  // 币种折算
  // ==========================================================================

  /** 折算到本位币（**缺任一汇率就是折不出来**，不按 1:1 算）。 */
  private BigDecimal convertToBase(
      List<ReportingMetricCardModels.MetricCardAmount> values,
      String baseCurrency,
      LocalDate asOf,
      UUID tenantId) {
    if (baseCurrency == null || values == null || values.isEmpty()) return null;
    var total = BigDecimal.ZERO;
    for (var value : values) {
      if (value.value() == null) return null;
      if (value.currency().equals(baseCurrency)) {
        total = total.add(value.value());
        continue;
      }
      var rate = resolveRate(tenantId, value.currency(), baseCurrency, asOf);
      if (rate == null) return null;
      total = total.add(value.value().multiply(rate).setScale(4, RoundingMode.HALF_UP));
    }
    return total;
  }

  /** 13.1.1.1 的按币种取值 → 本模块的按币种取值。 */
  private List<ReportingMetricCardModels.MetricCardAmount> toAmounts(
      List<ReportingOverviewModels.CurrencyValue> values) {
    var out = new ArrayList<ReportingMetricCardModels.MetricCardAmount>();
    if (values == null) return out;
    for (var value : values) {
      out.add(new ReportingMetricCardModels.MetricCardAmount(value.currency(), value.value()));
    }
    return out;
  }

  /** 取生效日不晚于窗口末的最近一条汇率快照（汇率必须带时点）。 */
  private BigDecimal resolveRate(
      UUID tenantId, String currency, String baseCurrency, LocalDate asOf) {
    if (tenantId == null) return null;
    var row =
        sql.fetchOne(
            "select rate from reporting_currency_rate_snapshot "
                + "where tenant_id = ? and currency = ? and base_currency = ? "
                + "and as_of <= ?::timestamptz order by as_of desc limit 1",
            tenantId,
            currency,
            baseCurrency,
            asOf.atStartOfDay(ZoneOffset.UTC).toOffsetDateTime());
    return row == null ? null : row.get(0, BigDecimal.class);
  }

  // ==========================================================================
  // 下钻
  // ==========================================================================

  /**
   * 下钻入口。
   *
   * <p>构成明细走 13.1.1.1 已有的下钻端点（**不新造一个**），报表页只指向**已存在的路由**
   * （指向未交付的页面是假承诺）。
   */
  private ReportingMetricCardModels.MetricCardDrilldown drilldown(
      ReportingMetricCardCatalog.CardSpec spec, UUID tenantId, Window window) {
    var hasDetail = hasDrilldown(tenantId, spec.metricCode(), window.from(), window.to());
    var detailPath =
        hasDetail
            ? "reporting/overview/drilldown?metricCode="
                + spec.metricCode()
                + "&from="
                + window.from()
                + "&to="
                + window.to()
                + "&scopeType=COMPANY"
            : null;
    var reportPath =
        spec.reportPath() == null
            ? null
            : spec.reportPath()
                + "?metric="
                + spec.metricCode()
                + "&from="
                + window.from()
                + "&to="
                + window.to();
    var reason =
        !hasDetail && reportPath == null
            ? "这个窗口里没有构成明细，也没有对应的已交付报表页 —— 卡片不显示下钻入口。"
            : !hasDetail
                ? "本服务没有这个指标的构成明细（构成明细由 13 的投影器登记）；下钻到口径归属系统的报表页。"
                : "";
    return new ReportingMetricCardModels.MetricCardDrilldown(
        detailPath != null || reportPath != null, detailPath, reportPath, spec.reportName(), reason);
  }

  private ReportingMetricCardModels.MetricCardDrilldown notConnectedDrilldown(
      ReportingMetricCardCatalog.CardSpec spec, String reason) {
    return new ReportingMetricCardModels.MetricCardDrilldown(false, null, null, null, reason);
  }

  /** 这个窗口里有没有构成明细（{@code reporting_drilldown_fact} 是既有表）。 */
  private boolean hasDrilldown(UUID tenantId, String metricCode, LocalDate from, LocalDate to) {
    var row =
        sql.fetchOne(
            "select count(*) from reporting_drilldown_fact "
                + "where tenant_id = ? and metric_code = ? and bucket_date >= ? and bucket_date <= ?",
            tenantId,
            metricCode,
            from,
            to);
    return row != null && row.get(0, Long.class) > 0;
  }

  // ==========================================================================
  // 时间窗口
  // ==========================================================================

  /** 窗口（带时区与口径说明）。 */
  private record Window(
      String preset,
      LocalDate from,
      LocalDate to,
      LocalDate asOfDate,
      String timezone,
      String timezoneSource,
      String note) {}

  /**
   * 解析时间窗口。
   *
   * <p>🔴 **「今日 / 昨日 / 本月」的边界按**本租户时区**算**：时区取自本租户投影事实上的
   * 分桶时区（{@code reporting_metric_fact.timezone}），**不写死 UTC**
   * （13.1.1.2 栽过一次：行里声称自己按 UTC 分桶，实际按的是别的时区）。
   */
  private Window resolveWindow(UUID tenantId, String preset, LocalDate from, LocalDate to) {
    var zone = tenantZone(tenantId);
    var asOf = LocalDate.now(clock.withZone(ZoneId.of(zone.zone())));
    var effective =
        preset == null || preset.isBlank() ? WINDOW_TODAY : preset.trim().toUpperCase(java.util.Locale.ROOT);
    switch (effective) {
      case WINDOW_TODAY -> {
        return new Window(WINDOW_TODAY, asOf, asOf, asOf, zone.zone(), zone.source(), "");
      }
      case WINDOW_YESTERDAY -> {
        var day = asOf.minusDays(1);
        return new Window(WINDOW_YESTERDAY, day, day, asOf, zone.zone(), zone.source(), "");
      }
      case WINDOW_THIS_MONTH -> {
        var start = asOf.withDayOfMonth(1);
        var end = asOf.withDayOfMonth(asOf.lengthOfMonth());
        var note =
            asOf.getDayOfMonth() == asOf.lengthOfMonth()
                ? ""
                : "本月尚未结束（今天到 "
                    + asOf
                    + "）：本月窗口按自然月整月计算，环比对比的是上月整月、"
                    + "同比对比的是去年同期整月，本月后续日期尚无事实。";
        return new Window(WINDOW_THIS_MONTH, start, end, asOf, zone.zone(), zone.source(), note);
      }
      case WINDOW_CUSTOM -> {
        if (from == null || to == null) {
          throw new ResponseStatusException(
              org.springframework.http.HttpStatus.BAD_REQUEST,
              "自定义窗口必须同时给 from 与 to（日期，yyyy-MM-dd）");
        }
        if (from.isAfter(to)) {
          throw new ResponseStatusException(
              org.springframework.http.HttpStatus.BAD_REQUEST,
              "起始日期不能晚于结束日期（from=" + from + "、to=" + to + "）");
        }
        return new Window(WINDOW_CUSTOM, from, to, asOf, zone.zone(), zone.source(), "");
      }
      default ->
          throw new ResponseStatusException(
              org.springframework.http.HttpStatus.BAD_REQUEST,
              "window 只能是 TODAY / YESTERDAY / THIS_MONTH / CUSTOM，收到：" + effective);
    }
  }

  /** 本租户的分桶时区（**服务端判定**，不来自请求参数）。 */
  private record TenantZone(String zone, String source) {}

  private TenantZone tenantZone(UUID tenantId) {
    var row =
        sql.fetchOne(
            "select timezone from reporting_metric_fact "
                + "where tenant_id = ? and timezone <> '' order by updated_at desc limit 1",
            tenantId);
    if (row != null) {
      var zone = row.get(0, String.class);
      if (zone != null && !zone.isBlank()) {
        try {
          ZoneId.of(zone);
          return new TenantZone(zone, "PROJECTION");
        } catch (RuntimeException ignored) {
          // 落库的是非法时区 id ⇒ 落回 UTC，并在响应里说清楚（不静默）。
        }
      }
    }
    return new TenantZone("UTC", "FALLBACK_UTC");
  }

  /** 对比期窗口（**紧邻的等长周期**）。 */
  private Window previousWindow(LocalDate from, LocalDate to) {
    var length = java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1;
    return new Window(
        "PREVIOUS", from.minusDays(length), from.minusDays(1), null, "", "", "");
  }

  /** 对比期窗口（去年同期，同长度）。 */
  private Window yearAgoWindow(LocalDate from, LocalDate to) {
    return new Window("YEAR_AGO", from.minusYears(1), to.minusYears(1), null, "", "", "");
  }

  // ==========================================================================
  // 回款
  // ==========================================================================

  /** 收款单的按币种金额（**上游算好的汇总**，本服务不自己加）。 */
  private List<ReportingMetricCardModels.MetricCardAmount> receiptAmounts(
      ReportingFinancePort.ReceiptPage page) {
    var out = new ArrayList<ReportingMetricCardModels.MetricCardAmount>();
    if (page == null || page.summaryByCurrency() == null) return out;
    for (var summary : page.summaryByCurrency()) {
      if (summary == null || summary.currency() == null) continue;
      out.add(new ReportingMetricCardModels.MetricCardAmount(summary.currency(), summary.amount()));
    }
    return out;
  }

  // ==========================================================================
  // 能力声明的静态事实
  // ==========================================================================

  /** 本服务**真的读过**的上游模块。 */
  private static final List<String> UPSTREAM_MODULES =
      List.of(
          "13.1.1.1 经营总览（reporting 自己的投影读模型：销售额 / 订单量 / 销量 / 毛利 / 库存总额）",
          "12.2.1.1 收款单（finance，回款：只引用上游算好的按币种合计）");

  /** **确实拿不到**的部分（每条写清原因与影响）。 */
  private static final List<String> NOT_DELIVERED =
      List.of(
          "**在途金额**：口径表里没有这个指标码；上游在途事实只给数量不给金额 —— "
              + "把数量乘一个成本价就是在代码里新写一条指标公式（会出现第二个真相）。"
              + "归属：06 采购在途 / 07 库存在途。",
          "**回款的构成明细**：reporting 的构成明细表由 13 的投影器登记，收款单在 12 财务，"
              + "所以本服务没有回款的构成明细行 —— 下钻到口径归属系统的报表页（12.2.1.1 收款单）。",
          "**毛利没有单一构成明细**：毛利是派生指标（销售额 − 成本），构成分散在两个分子分母指标里，"
              + "构成明细表里没有「毛利」这一行 —— 下钻到 13.1.1.1 经营总览。");

  /** 口径纪律说明。 */
  private static final List<String> NOTES =
      List.of(
          "口径**单点定义**在 13（reporting_metric_definition）：本模块只引用，不重算 —— "
              + "毛利这类派生指标在口径表里只写「分子 / 分母引用」，不写公式。",
          "「没有数据」与「数据是 0」是两件事：事实不存在、口径未登记、上游读不到，"
              + "三种情况一律显示「—」并写明原因，绝不显示成 0。",
          "多币种**绝不相加**：涉币指标按币种分列；给了本位币且有汇率快照才给本位币合计，"
              + "折不出来就是不可判定（不是按 1:1 算）。",
          "「今日 / 昨日 / 本月」的边界按**本租户时区**算（取自本租户投影事实的分桶时区），不写死 UTC。",
          "工作台**只读 + 跳转**：本模块不提供任何写端点，也不下发租户号与范围参数 —— "
              + "范围由服务端判定，前端只读服务端已经过滤好的投影。");
}
