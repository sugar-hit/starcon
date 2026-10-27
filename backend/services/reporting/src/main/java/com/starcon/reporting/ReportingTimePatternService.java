package com.starcon.reporting;

import com.starcon.tenancy.Ids;
import com.starcon.tenancy.TenantTransactions;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Service;

/**
 * 13.2.1.2 分时与分周模式的**读模型**（蓝图「输入」句：订单时间戳（按店铺时区换算）；时段配置）。
 *
 * <p><b>🔴 三条纪律</b>：
 *
 * <ol>
 *   <li><b>口径不重算</b>：公司维度的三个基础口径（{@code SALES_AMOUNT} / {@code UNIT_SOLD} /
 *       {@code ORDER_COUNT}）来自 13.1.1.1 的 {@code reporting_metric_definition}
 *       （<b>本模块引用不重定义</b>），本模块新增的时段占比 / P90 阈值来自
 *       {@code reporting_time_pattern_metric_definition}；本类只<b>按定义聚合</b>。
 *   <li><b>「算不出来」与「等于 0」必须分开</b>：分母为 0、事实缺失，
 *       一律返回 {@code null} / 「不可比」，<b>不用 0 冒充</b>。
 *   <li><b>不跨币种相加</b>：成交金额按币种分别成列；订单数与件数与币种无关，给一个合计。
 * </ol>
 *
 * <p><b>🔴 汇总口径（跨店铺汇总时对齐的是哪一个槽位）</b>：默认
 * {@link ReportingTimePatternCatalog.Alignment#LOCAL_TIME}（<b>店铺本地时间对齐</b>）——
 * 「小时 17」= 每个店铺<b>各自</b>的本地 17:00–17:59。理由与备选口径
 * （{@code UTC_SLOT}）都写在 {@link ReportingTimePatternCatalog#alignmentNote}，
 * 并随响应一起返回 ⇒ 页面必须显示它。
 *
 * <p><b>🔴 占比的单位</b>：内部一律用<b>分数</b>（0..1）参与比较；
 * 出现在响应里的 {@code orderShare} 是<b>百分数</b>（12.345678 = 12.345678%），
 * 与本仓其它模块的前端格式化（{@code percentText}）一致。
 */
@Service
public class ReportingTimePatternService {

  /** 占比的定标位数（分数与百分数同用）。 */
  static final int SHARE_SCALE = 6;

  private final DSLContext sql;
  private final TenantTransactions tenants;
  private final ReportingTimePatternRefreshService refresh;

  public ReportingTimePatternService(
      DSLContext sql, TenantTransactions tenants, ReportingTimePatternRefreshService refresh) {
    this.sql = sql;
    this.tenants = tenants;
    this.refresh = refresh;
  }

  // ==========================================================================
  // 口径目录
  // ==========================================================================

  /**
   * 读该租户的**分时口径目录**（口径唯一的展示形态）。
   *
   * <p>⚠️ 公司维度的三个基础口径来自 13.1.1.1 的口径表（<b>本模块引用，不重定义</b>），
   * 目录里用 {@code referencedFrom} 说清「它在哪定义」。
   */
  public List<ReportingTimePatternModels.TimePatternMetricDefinitionView> metricDefinitions(
      UUID tenantId) {
    return tenants.run(
        tenantId,
        () -> {
          refresh.seedDefinitions(tenantId);
          return loadDefinitions(tenantId);
        });
  }

  private List<ReportingTimePatternModels.TimePatternMetricDefinitionView> loadDefinitions(
      UUID tenantId) {
    var out = new ArrayList<ReportingTimePatternModels.TimePatternMetricDefinitionView>();
    for (var row :
        sql.fetch(
            """
            select metric_code, display_name, unit, aggregation, currency_aware, description, owner_module
              from reporting_metric_definition
             where tenant_id = ? and enabled
               and metric_code in (?,?,?)
             order by metric_code
            """,
            tenantId,
            ReportingMetricCatalog.SALES_AMOUNT,
            ReportingMetricCatalog.UNIT_SOLD,
            ReportingMetricCatalog.ORDER_COUNT)) {
      out.add(
          new ReportingTimePatternModels.TimePatternMetricDefinitionView(
              row.get(0, String.class),
              row.get(1, String.class),
              row.get(2, String.class),
              row.get(3, String.class),
              null,
              Boolean.TRUE.equals(row.get(4, Boolean.class)),
              row.get(5, String.class),
              row.get(6, String.class),
              "reporting_metric_definition（13.1.1.1 经营总览定义，本模块**引用不重定义**）"));
    }
    for (var row :
        sql.fetch(
            """
            select metric_code, display_name, unit, aggregation, base_metric_code,
                   currency_aware, description, owner_module
              from reporting_time_pattern_metric_definition
             where tenant_id = ? and enabled
             order by metric_code
            """,
            tenantId)) {
      out.add(
          new ReportingTimePatternModels.TimePatternMetricDefinitionView(
              row.get(0, String.class),
              row.get(1, String.class),
              row.get(2, String.class),
              row.get(3, String.class),
              row.get(4, String.class),
              Boolean.TRUE.equals(row.get(5, Boolean.class)),
              row.get(6, String.class),
              row.get(7, String.class),
              "reporting_time_pattern_metric_definition（本模块 13.2.1.2 定义）"));
    }
    return out;
  }

  // ==========================================================================
  // 店铺时区档案
  // ==========================================================================

  /** 该租户的店铺时区档案（含**定不了时区**的店铺与原因）。 */
  public List<ReportingTimePatternModels.TimePatternShopView> shops(UUID tenantId) {
    return tenants.run(tenantId, () -> loadShops(tenantId));
  }

  private List<ReportingTimePatternModels.TimePatternShopView> loadShops(UUID tenantId) {
    var out = new ArrayList<ReportingTimePatternModels.TimePatternShopView>();
    for (var row :
        sql.fetch(
            """
            select shop_code, shop_name, shop_timezone, timezone_status, timezone_reason
              from reporting_time_pattern_shop_profile
             where tenant_id = ?
             order by shop_code
            """,
            tenantId)) {
      out.add(
          new ReportingTimePatternModels.TimePatternShopView(
              row.get(0, String.class),
              row.get(1, String.class),
              row.get(2, String.class),
              row.get(3, String.class),
              row.get(4, String.class)));
    }
    return out;
  }

  // ==========================================================================
  // 主查询
  // ==========================================================================

  /**
   * 分时与分周模式：热力图 + 小时 / 星期分布 + 时段 + 高峰时段排序。
   *
   * @param alignment 跨店铺汇总的对齐口径（见 {@link ReportingTimePatternCatalog.Alignment}）
   * @param shopCodes 只看这些店铺（可空 ⇒ 全部）
   * @param rankingMetricCode 高峰排序依据的指标码（可空 ⇒ 成交订单数）
   */
  public ReportingTimePatternModels.TimePatternResponse pattern(
      UUID tenantId,
      LocalDate from,
      LocalDate to,
      ReportingTimePatternCatalog.Alignment alignment,
      List<String> shopCodes,
      String rankingMetricCode) {
    return tenants.run(
        tenantId,
        () -> {
          refresh.seedDefinitions(tenantId);
          refresh.seedSlots(tenantId);
          var ranking =
              rankingMetricCode == null || rankingMetricCode.isBlank()
                  ? ReportingMetricCatalog.ORDER_COUNT
                  : rankingMetricCode.trim();
          if (!List.of(
                  ReportingMetricCatalog.ORDER_COUNT,
                  ReportingMetricCatalog.UNIT_SOLD,
                  ReportingMetricCatalog.SALES_AMOUNT)
              .contains(ranking)) {
            throw new IllegalArgumentException(
                "高峰排序依据的指标只能是 ORDER_COUNT / UNIT_SOLD / SALES_AMOUNT：" + ranking);
          }

          var cells = loadCells(tenantId, from, to, alignment, shopCodes);
          var total = new Summary();
          var byHour = new Summary[24];
          for (int hour = 0; hour < 24; hour++) byHour[hour] = new Summary();
          var byWeekday = new Summary[8];
          for (int weekday = 1; weekday <= 7; weekday++) byWeekday[weekday] = new Summary();
          var byCell = new LinkedHashMap<String, Summary>();
          for (var entry : cells.entrySet()) {
            var key = entry.getKey();
            var agg = entry.getValue();
            total.plus(agg, key.metric());
            byHour[key.hour()].plus(agg, key.metric());
            byWeekday[key.weekday()].plus(agg, key.metric());
            byCell.computeIfAbsent(key.weekday() + ":" + key.hour(), c -> new Summary())
                .plus(agg, key.metric());
          }

          // 🔴 **P90 阈值**：24 个小时桶**全部**参与（没有事实的小时算 0）。
          //   只拿有单的小时算分位会把高峰刻度抬高。
          var hourCounts = new ArrayList<BigDecimal>();
          for (var hour = 0; hour < 24; hour++) hourCounts.add(byHour[hour].orderCount);
          var p90 = percentile90(hourCounts);
          var p90Hours = new LinkedHashSet<Integer>();
          if (p90 != null) {
            for (int hour = 0; hour < 24; hour++) {
              if (byHour[hour].orderCount.compareTo(p90) >= 0) p90Hours.add(hour);
            }
          }

          var totalOrderCount = total.orderCount;
          var totalFraction = fraction(totalOrderCount, totalOrderCount);
          var totalAmounts = total.amounts;

          // ---- 热力图（固定的 7 × 24 网格）----
          var heatmap = new ArrayList<ReportingTimePatternModels.TimePatternCell>();
          for (int weekday = 1; weekday <= 7; weekday++) {
            for (int hour = 0; hour < 24; hour++) {
              var summary = byCell.getOrDefault(weekday + ":" + hour, new Summary());
              heatmap.add(
                  new ReportingTimePatternModels.TimePatternCell(
                      weekday,
                      ReportingTimePatternCatalog.weekdayLabel(weekday),
                      hour,
                      ReportingTimePatternCatalog.hourLabel(hour),
                      summary.orderCount,
                      summary.unitSold,
                      amountList(summary.amounts),
                      percent(fraction(summary.orderCount, totalOrderCount)),
                      p90Hours.contains(hour)));
            }
          }

          // ---- 小时分布 / 星期分布 ----
          var hourDistribution = new ArrayList<ReportingTimePatternModels.TimePatternHourBucket>();
          for (int hour = 0; hour < 24; hour++) {
            var summary = byHour[hour];
            hourDistribution.add(
                new ReportingTimePatternModels.TimePatternHourBucket(
                    hour,
                    ReportingTimePatternCatalog.hourLabel(hour),
                    summary.orderCount,
                    summary.unitSold,
                    amountList(summary.amounts),
                    percent(fraction(summary.orderCount, totalOrderCount)),
                    p90Hours.contains(hour)));
          }
          var weekdayDistribution =
              new ArrayList<ReportingTimePatternModels.TimePatternWeekdayBucket>();
          for (int weekday = 1; weekday <= 7; weekday++) {
            var summary = byWeekday[weekday];
            weekdayDistribution.add(
                new ReportingTimePatternModels.TimePatternWeekdayBucket(
                    weekday,
                    ReportingTimePatternCatalog.weekdayLabel(weekday),
                    summary.orderCount,
                    summary.unitSold,
                    amountList(summary.amounts),
                    percent(fraction(summary.orderCount, totalOrderCount))));
          }

          // ---- 时段（配置算出来的区间判定，不是 if-else 硬编码）----
          var slotViews = new ArrayList<ReportingTimePatternModels.TimePatternSlotView>();
          for (var config : loadSlotConfigs(tenantId)) {
            var summary = new Summary();
            for (int hour = config.startHour(); hour < config.endHour(); hour++) {
              summary.absorb(byHour[hour]);
            }
            var slotFraction = fraction(summary.orderCount, totalOrderCount);
            var actual =
                "ORDER_SHARE".equals(config.qualifyBasis())
                    ? slotFraction
                    : summary.orderCount;
            var qualified = qualifies(config, actual);
            slotViews.add(
                new ReportingTimePatternModels.TimePatternSlotView(
                    config.slotCode(),
                    config.displayName(),
                    config.startHour(),
                    config.endHour(),
                    config.level(),
                    ReportingTimePatternCatalog.levelLabel(config.level()),
                    config.qualifyBasis(),
                    config.qualifyComparison(),
                    config.qualifyThreshold(),
                    qualified,
                    slotReason(config, actual, qualified),
                    summary.orderCount,
                    summary.unitSold,
                    amountList(summary.amounts),
                    percent(slotFraction)));
          }

          // ---- 高峰时段排序 ----
          var peakRanking = rankSlots(slotViews, ranking, totalAmounts.keySet());

          // ---- 店铺时区档案 + 定不了时区的那部分 ----
          var shopViews = loadShops(tenantId);
          var unattributed = loadUnattributed(tenantId, from, to, shopCodes);

          // ---- 覆盖度 ----
          var coverage = coverage(tenantId, from, to, totalOrderCount, alignment);

          var dataUpdatedAt = dataUpdatedAt(tenantId);
          var anyFacts = hasAnyFact(tenantId);
          var status = !anyFacts ? "NOT_PROJECTED" : "PROJECTED";
          var emptyReason = anyFacts ? "NO_FACTS_IN_WINDOW" : "NOT_PROJECTED";
          var emptyNote =
              anyFacts
                  ? "这个窗口里没有分时事实（该租户**有**投影，只是这个窗口没有数据）—— "
                      + "换时间范围或先刷新投影。这是「这个窗口没有」，不是「读不到」。"
                  : "这个租户还没有投影过分时事实 —— 先刷新投影（POST /reporting/time-pattern/refresh）。"
                      + "这是「还没有数据」，不是「读不到」。";

          var warnings = new ArrayList<String>();
          warnings.add(ReportingTimePatternCatalog.alignmentNote(alignment));
          warnings.add(
              "🔴 **跨店铺汇总必须说清对齐的是哪一个槽位**："
                  + (alignment == ReportingTimePatternCatalog.Alignment.UTC_SLOT
                      ? "当前是「UTC 槽位对齐」—— 回答的是仓内同一绝对时刻的压力。"
                      : "当前是「店铺本地时间对齐」—— 回答的是买家在什么时间点下单。")
                  + "两种口径**结果不同**，切换会得到不同的热力图。");
          warnings.add(
              "金额**按币种分别列出**，**绝不跨币种相加**；订单数与销量件数与币种无关，给一个合计。");
          warnings.add(
              "🔴 **「星期几」是店铺本地时区的星期几**（ISO 1 = 周一 … 7 = 周日），"
                  + "**不是** ISO 周的**周数** —— 周数是另一回事，本模块不产出周数。");
          warnings.add(
              "热力图是**固定的 7 × 24 网格**，没有事实的格子显示 0；" + coverage.note());
          if (!unattributed.shopCodes().isEmpty()) {
            warnings.add(
                "⚠️ 有 "
                    + unattributed.shopCodes().size()
                    + " 个店铺**定不了时区**（"
                    + String.join("、", unattributed.shopCodes())
                    + "）⇒ 它们的订单**不进任何本地小时 / 星期桶**，"
                    + "单列在「未能按店铺本地时分桶」里（不摊给任何时段，摊给谁是造假）。");
          }

          var definitions = loadDefinitions(tenantId);
          var rankingLabel = displayName(definitions, ranking);
          return new ReportingTimePatternModels.TimePatternResponse(
              from,
              to,
              from,
              to,
              alignment.name(),
              ReportingTimePatternCatalog.alignmentLabel(alignment),
              ReportingTimePatternCatalog.alignmentNote(alignment),
              shopCodes == null ? List.of() : shopCodes,
              heatmap,
              hourDistribution,
              weekdayDistribution,
              slotViews,
              peakRanking,
              ranking,
              rankingLabel,
              p90,
              shopViews,
              unattributed,
              coverage,
              definitions,
              dataUpdatedAt,
              "最近一次投影落库时刻（报表不是实时的）。",
              status,
              emptyReason,
              emptyNote,
              warnings);
        });
  }

  // ==========================================================================
  // 取数
  // ==========================================================================

  /** 事实的分组键（星期几 × 小时 × 指标 × 币种）。 */
  private record Key(int weekday, int hour, String metric, String currency) {}

  /** 一个分组键上的合计。 */
  private record Agg(String currency, BigDecimal value, BigDecimal quantity) {}

  /** 一个桶的汇总（可变累加器）。 */
  private static final class Summary {
    BigDecimal orderCount = BigDecimal.ZERO;
    BigDecimal unitSold = BigDecimal.ZERO;
    final Map<String, BigDecimal> amounts = new LinkedHashMap<String, BigDecimal>();

    void plus(Agg agg, String metric) {
      if (agg == null) return;
      if (ReportingMetricCatalog.ORDER_COUNT.equals(metric)) {
        orderCount = orderCount.add(agg.value());
      } else if (ReportingMetricCatalog.UNIT_SOLD.equals(metric)) {
        unitSold = unitSold.add(agg.quantity());
      } else if (ReportingMetricCatalog.SALES_AMOUNT.equals(metric)) {
        amounts.merge(agg.currency(), agg.value(), BigDecimal::add);
      }
    }

    /** 吸收另一个桶（不区分指标 —— 调用方已经按指标分好）。 */
    void absorb(Summary other) {
      orderCount = orderCount.add(other.orderCount);
      unitSold = unitSold.add(other.unitSold);
      for (var entry : other.amounts.entrySet()) {
        amounts.merge(entry.getKey(), entry.getValue(), BigDecimal::add);
      }
    }
  }

  /** 加载分组后的分时事实。 */
  private Map<Key, Agg> loadCells(
      UUID tenantId,
      LocalDate from,
      LocalDate to,
      ReportingTimePatternCatalog.Alignment alignment,
      List<String> shopCodes) {
    var args = new ArrayList<Object>();
    var filters = new ArrayList<String>();
    String weekdayExpr;
    String hourExpr;
    String rangeFilter;
    if (alignment == ReportingTimePatternCatalog.Alignment.UTC_SLOT) {
      weekdayExpr = "cast(extract(isodow from occurred_at at time zone 'UTC') as int)";
      hourExpr = "cast(extract(hour from occurred_at at time zone 'UTC') as int)";
      // 🔴 **定不了时区的订单不进任何格子**（LOCAL_TIME 与 UTC_SLOT **同一套订单集合**）：
      //   UTC 槽位是从 `occurred_at` 算的，不看店铺时区 ⇒ 若不显式排除，
      //   这批订单会**同时**出现在热力图**和**「未能按店铺本地时分桶」里（被数两次）。
      //   排除掉之后，两个口径的**差别只剩「槽位怎么对齐」**这一件事 ——
      //   「两者结果不同」才有意义，也才说得清。
      rangeFilter =
          "occurred_at >= ?::timestamptz and occurred_at < ?::timestamptz"
              + " and timezone_status = 'RESOLVED'";
      args.add(tenantId);
      args.add(from.atStartOfDay(ZoneOffset.UTC).toOffsetDateTime());
      args.add(to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toOffsetDateTime());
    } else {
      weekdayExpr = "local_weekday";
      hourExpr = "local_hour";
      // ⚠️ `local_date` 为 NULL 的行（定不了时区的那批）本来就被 `between` 排除，
      //    这里仍然**显式**写上 `timezone_status = 'RESOLVED'` —— 两个口径用同一个判据。
      rangeFilter = "local_date between ? and ? and timezone_status = 'RESOLVED'";
      args.add(tenantId);
      args.add(from);
      args.add(to);
    }
    if (shopCodes != null && !shopCodes.isEmpty()) {
      filters.add("shop_code in (" + placeholders(shopCodes.size()) + ")");
      args.addAll(shopCodes);
    }
    var where = filters.isEmpty() ? "" : " and " + String.join(" and ", filters);
    var query =
        """
        select w, h, metric_code, currency, sum(v) as v, sum(q) as q
          from (
            select %s as w, %s as h, metric_code, currency, value as v, quantity as q
              from reporting_time_pattern_fact
             where tenant_id = ? and %s%s
          ) t
         group by w, h, metric_code, currency
        """
            .formatted(weekdayExpr, hourExpr, rangeFilter, where);
    var out = new LinkedHashMap<Key, Agg>();
    for (var row : sql.fetch(query, args.toArray())) {
      var key =
          new Key(
              row.get(0, Integer.class),
              row.get(1, Integer.class),
              row.get(2, String.class),
              row.get(3, String.class));
      out.put(
          key,
          new Agg(
              row.get(3, String.class),
              row.get(4, BigDecimal.class),
              row.get(5, BigDecimal.class)));
    }
    return out;
  }

  // ==========================================================================
  // 派生量
  // ==========================================================================

  /** 占比**分数**（分母为 0 ⇒ null，不是 0%）。 */
  static BigDecimal fraction(BigDecimal value, BigDecimal total) {
    if (value == null || total == null || total.signum() == 0) return null;
    return value.divide(total, SHARE_SCALE, RoundingMode.HALF_UP);
  }

  /** 占比**百分数**（分数 × 100；分数为 null ⇒ null）。 */
  static BigDecimal percent(BigDecimal fraction) {
    if (fraction == null) return null;
    return fraction.multiply(new BigDecimal("100")).setScale(SHARE_SCALE, RoundingMode.HALF_UP);
  }

  /**
   * P90 分位（线性插值，与 PostgreSQL 的 {@code percentile_cont} 同一定义）。
   *
   * <p>⚠️ 全程 {@link BigDecimal}，**不碰浮点**。
   */
  static BigDecimal percentile90(List<BigDecimal> values) {
    if (values == null || values.isEmpty()) return null;
    var sorted = new ArrayList<>(values);
    sorted.sort(Comparator.naturalOrder());
    if (sorted.size() == 1) return sorted.get(0);
    var rank = new BigDecimal("0.9").multiply(BigDecimal.valueOf(sorted.size() - 1));
    var lower = rank.setScale(0, RoundingMode.FLOOR).intValue();
    var frac = rank.subtract(BigDecimal.valueOf(lower));
    var low = sorted.get(lower);
    if (frac.signum() == 0) return low;
    var high = sorted.get(Math.min(lower + 1, sorted.size() - 1));
    return low.add(frac.multiply(high.subtract(low))).setScale(SHARE_SCALE, RoundingMode.HALF_UP);
  }

  /** 各币种金额列表（**按币种分列，绝不相加**）。 */
  private static List<ReportingTimePatternModels.TimePatternAmount> amountList(
      Map<String, BigDecimal> amounts) {
    var out = new ArrayList<ReportingTimePatternModels.TimePatternAmount>();
    for (var entry : amounts.entrySet()) {
      out.add(
          new ReportingTimePatternModels.TimePatternAmount(
              entry.getKey(), entry.getValue().setScale(4, RoundingMode.HALF_UP)));
    }
    return out;
  }

  /** 判定一个时段是否达标（依据来自**时段配置**）。 */
  private static boolean qualifies(SlotConfig config, BigDecimal actual) {
    if (actual == null) return false;
    var threshold = config.qualifyThreshold();
    if (threshold == null) return false;
    return "AT_MOST".equals(config.qualifyComparison())
        ? actual.compareTo(threshold) <= 0
        : actual.compareTo(threshold) >= 0;
  }

  /** 判定理由（页面上原样显示 —— 用户要看得懂「为什么这个时段算高峰」）。 */
  private static String slotReason(SlotConfig config, BigDecimal actual, boolean qualified) {
    var shareBasis = "ORDER_SHARE".equals(config.qualifyBasis());
    var basisText = shareBasis ? "订单占比" : "订单笔数";
    var compareText = "AT_MOST".equals(config.qualifyComparison()) ? "≤" : "≥";
    var thresholdText =
        shareBasis
            ? config.qualifyThreshold()
                .multiply(new BigDecimal("100"))
                .setScale(4, RoundingMode.HALF_UP)
                .toPlainString()
                + "%"
            : config.qualifyThreshold().setScale(0, RoundingMode.HALF_UP).toPlainString();
    var head =
        "配置层级："
            + ReportingTimePatternCatalog.levelLabel(config.level())
            + "（租户自己标的）；判定："
            + basisText
            + " "
            + compareText
            + " "
            + thresholdText
            + "。";
    if (actual == null) {
      return head
          + "⚠️ 本窗口该时段**没有事实** ⇒ "
          + basisText
          + "算不出来（**不是 0**），不判达标。";
    }
    var actualText =
        shareBasis
            ? actual.multiply(new BigDecimal("100")).setScale(4, RoundingMode.HALF_UP).toPlainString() + "%"
            : actual.setScale(0, RoundingMode.HALF_UP).toPlainString();
    return head
        + "本窗口该时段"
        + basisText
        + " "
        + actualText
        + " ⇒ "
        + (qualified
            ? "**达标**（这个时段确实算"
                + ReportingTimePatternCatalog.levelLabel(config.level())
                + "）。"
            : "**未达标**（这个时段**不**算"
                + ReportingTimePatternCatalog.levelLabel(config.level())
                + "）。");
  }

  // ==========================================================================
  // 时段配置
  // ==========================================================================

  /** 一条时段配置（表里的形态）。 */
  private record SlotConfig(
      String id,
      String slotCode,
      String displayName,
      int startHour,
      int endHour,
      String level,
      String qualifyBasis,
      String qualifyComparison,
      BigDecimal qualifyThreshold,
      int sortOrder,
      boolean enabled,
      String description) {}

  private List<SlotConfig> loadSlotConfigs(UUID tenantId) {
    var out = new ArrayList<SlotConfig>();
    for (var row :
        sql.fetch(
            """
            select id, slot_code, display_name, start_hour, end_hour, level, qualify_basis,
                   qualify_comparison, qualify_threshold, sort_order, enabled, description
              from reporting_time_pattern_slot
             where tenant_id = ? and enabled
             order by start_hour, sort_order, slot_code
            """,
            tenantId)) {
      out.add(
          new SlotConfig(
              row.get(0, UUID.class).toString(),
              row.get(1, String.class),
              row.get(2, String.class),
              row.get(3, Integer.class),
              row.get(4, Integer.class),
              row.get(5, String.class),
              row.get(6, String.class),
              row.get(7, String.class),
              row.get(8, BigDecimal.class),
              row.get(9, Integer.class),
              Boolean.TRUE.equals(row.get(10, Boolean.class)),
              row.get(11, String.class)));
    }
    return out;
  }

  /**
   * 该租户的时段配置（含停用的）。
   *
   * <p>🔴 <b>读之前先确保初始配置存在</b>（与 {@link #metricDefinitions} / {@link #pattern} 同一纪律）：
   * 时段配置是<b>租户可配置的数据</b>，但**第一次来就得有一份可用的初始划分** ——
   * 否则一个刚接入的租户打开这一页，看到的是<b>空列表</b>，
   * 读起来像「这个功能没有数据」而不是「还没配过」。
   * ⚠️ 播种只在「这个租户一条都没有」时发生（见
   * {@link ReportingTimePatternRefreshService#seedSlots}）⇒ 租户自己配好的划分不会被冲掉。
   */
  public List<ReportingTimePatternModels.TimePatternSlotConfigView> slotConfigs(UUID tenantId) {
    return tenants.run(
        tenantId,
        () -> {
          refresh.seedSlots(tenantId);
          var out = new ArrayList<ReportingTimePatternModels.TimePatternSlotConfigView>();
          for (var row :
              sql.fetch(
                  """
                  select id, slot_code, display_name, start_hour, end_hour, level, qualify_basis,
                         qualify_comparison, qualify_threshold, sort_order, enabled, description
                    from reporting_time_pattern_slot
                   where tenant_id = ?
                   order by start_hour, sort_order, slot_code
                  """,
                  tenantId)) {
            out.add(
                new ReportingTimePatternModels.TimePatternSlotConfigView(
                    row.get(0, UUID.class).toString(),
                    row.get(1, String.class),
                    row.get(2, String.class),
                    row.get(3, Integer.class),
                    row.get(4, Integer.class),
                    row.get(5, String.class),
                    ReportingTimePatternCatalog.levelLabel(row.get(5, String.class)),
                    row.get(6, String.class),
                    row.get(7, String.class),
                    row.get(8, BigDecimal.class),
                    row.get(9, Integer.class),
                    Boolean.TRUE.equals(row.get(10, Boolean.class)),
                    row.get(11, String.class),
                    ReportingTimePatternCatalog.BOUNDARY_NOTE));
          }
          return out;
        });
  }

  // ==========================================================================
  // 高峰排序
  // ==========================================================================

  private List<ReportingTimePatternModels.TimePatternPeakRank> rankSlots(
      List<ReportingTimePatternModels.TimePatternSlotView> slots,
      String rankingMetric,
      Set<String> currencies) {
    var out = new ArrayList<ReportingTimePatternModels.TimePatternPeakRank>();
    if (ReportingMetricCatalog.SALES_AMOUNT.equals(rankingMetric)) {
      // 🔴 **金额涉币 ⇒ 按币种各自排名**（跨币种比大小本来就没有意义）。
      for (var currency : currencies) {
        var ordered = new ArrayList<>(slots);
        ordered.sort(
            Comparator.comparing(
                (ReportingTimePatternModels.TimePatternSlotView slot) ->
                    amountOf(slot.salesAmounts(), currency),
                Comparator.nullsLast(Comparator.reverseOrder())));
        var rank = 0;
        for (var slot : ordered) {
          var value = amountOf(slot.salesAmounts(), currency);
          if (value == null) continue;
          rank++;
          out.add(
              new ReportingTimePatternModels.TimePatternPeakRank(
                  rank,
                  slot.slotCode(),
                  slot.displayName(),
                  slot.level(),
                  slot.levelLabel(),
                  rankingMetric,
                  "成交金额",
                  value,
                  currency,
                  slot.orderShare(),
                  slot.qualified(),
                  slot.reason()));
        }
      }
      return out;
    }
    var ordered = new ArrayList<>(slots);
    var byCount = ReportingMetricCatalog.ORDER_COUNT.equals(rankingMetric);
    ordered.sort(
        Comparator.comparing(
            (ReportingTimePatternModels.TimePatternSlotView slot) ->
                byCount ? slot.orderCount() : slot.unitSold(),
            Comparator.nullsLast(Comparator.reverseOrder())));
    var rank = 0;
    for (var slot : ordered) {
      rank++;
      out.add(
          new ReportingTimePatternModels.TimePatternPeakRank(
              rank,
              slot.slotCode(),
              slot.displayName(),
              slot.level(),
              slot.levelLabel(),
              rankingMetric,
              byCount ? "成交订单数" : "销量（件）",
              byCount ? slot.orderCount() : slot.unitSold(),
              "",
              slot.orderShare(),
              slot.qualified(),
              slot.reason()));
    }
    return out;
  }

  private static BigDecimal amountOf(
      List<ReportingTimePatternModels.TimePatternAmount> amounts, String currency) {
    for (var amount : amounts) {
      if (amount.currency().equals(currency)) return amount.value();
    }
    return null;
  }

  private static String displayName(
      List<ReportingTimePatternModels.TimePatternMetricDefinitionView> definitions, String code) {
    for (var definition : definitions) {
      if (definition.metricCode().equals(code)) return definition.displayName();
    }
    return code;
  }

  // ==========================================================================
  // 定不了时区的那部分
  // ==========================================================================

  private ReportingTimePatternModels.TimePatternUnattributed loadUnattributed(
      UUID tenantId, LocalDate from, LocalDate to, List<String> shopCodes) {
    var args = new ArrayList<Object>();
    args.add(tenantId);
    args.add(from.atStartOfDay(ZoneOffset.UTC).toOffsetDateTime());
    args.add(to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toOffsetDateTime());
    var where = "";
    if (shopCodes != null && !shopCodes.isEmpty()) {
      where = " and shop_code in (" + placeholders(shopCodes.size()) + ")";
      args.addAll(shopCodes);
    }
    var query =
        """
        select metric_code, currency, sum(v) as v, sum(q) as q, shop_code
          from (
            select metric_code, currency, value as v, quantity as q, shop_code
              from reporting_time_pattern_fact
             where tenant_id = ? and timezone_status = 'UNRESOLVED'
               and occurred_at >= ?::timestamptz and occurred_at < ?::timestamptz%s
          ) t
         group by metric_code, currency, shop_code
        """
            .formatted(where);
    var orderCount = BigDecimal.ZERO;
    var unitSold = BigDecimal.ZERO;
    var amounts = new LinkedHashMap<String, BigDecimal>();
    var shops = new LinkedHashSet<String>();
    for (var row : sql.fetch(query, args.toArray())) {
      var metric = row.get(0, String.class);
      var currency = row.get(1, String.class);
      var value = row.get(2, BigDecimal.class);
      var quantity = row.get(3, BigDecimal.class);
      shops.add(row.get(4, String.class));
      if (ReportingMetricCatalog.ORDER_COUNT.equals(metric)) {
        orderCount = orderCount.add(value);
      } else if (ReportingMetricCatalog.UNIT_SOLD.equals(metric)) {
        unitSold = unitSold.add(quantity);
      } else if (ReportingMetricCatalog.SALES_AMOUNT.equals(metric)) {
        amounts.merge(currency, value, BigDecimal::add);
      }
    }
    return new ReportingTimePatternModels.TimePatternUnattributed(
        orderCount.setScale(4, RoundingMode.HALF_UP),
        unitSold.setScale(6, RoundingMode.HALF_UP),
        amountList(amounts),
        "这些订单的店铺**定不了时区**（启用多个不同时区的站点，或没启用站点 / 上游站点目录查不到时区）"
            + "⇒ 它们没有「店铺本地小时 / 星期几」，**不进任何时段与格子**。"
            + "⚠️ 这一块的窗口按 **UTC 日期**切（没有本地日期可切）—— 它与热力图的口径**不完全一致**，"
            + "所以它**不计入**热力图的分母。",
        new ArrayList<>(shops));
  }

  // ==========================================================================
  // 覆盖度与状态
  // ==========================================================================

  private ReportingTimePatternModels.TimePatternCoverage coverage(
      UUID tenantId,
      LocalDate from,
      LocalDate to,
      BigDecimal projectedOrderCount,
      ReportingTimePatternCatalog.Alignment alignment) {
    var expected = (int) (to.toEpochDay() - from.toEpochDay() + 1);
    var days =
        sql.fetch(
            """
            select count(distinct local_date) from reporting_time_pattern_fact
             where tenant_id = ? and local_date between ? and ?
            """,
            tenantId,
            from,
            to);
    var projectedDays = days.isEmpty() ? 0 : days.get(0).get(0, Integer.class);
    var note =
        "本窗口共 "
            + expected
            + " 天，实际投影到 "
            + projectedDays
            + " 天"
            + (projectedDays < expected
                ? "（**投影不满**）—— 格子里的 0 里，有多少是真没单、有多少是没投影，"
                    + "分不清；请先补齐投影再看热力图。"
                : "（投影已覆盖整个窗口）。")
            + "⚠️ 覆盖度按**店铺本地日期**统计。";
    return new ReportingTimePatternModels.TimePatternCoverage(
        projectedOrderCount, projectedDays, expected, note);
  }

  private boolean hasAnyFact(UUID tenantId) {
    var row =
        sql.fetch("select 1 from reporting_time_pattern_fact where tenant_id = ? limit 1", tenantId);
    return !row.isEmpty();
  }

  private java.time.OffsetDateTime dataUpdatedAt(UUID tenantId) {
    var row =
        sql.fetchOne(
            "select max(updated_at) from reporting_time_pattern_fact where tenant_id = ?", tenantId);
    return row == null ? null : row.get(0, java.time.OffsetDateTime.class);
  }

  // ==========================================================================
  // 小工具
  // ==========================================================================

  private static String placeholders(int count) {
    var sb = new StringBuilder();
    for (int i = 0; i < count; i++) {
      if (i > 0) sb.append(",");
      sb.append("?");
    }
    return sb.toString();
  }

  /** 时段配置的新增 / 修改（写库前的校验形态）。 */
  record SlotInput(
      String slotCode,
      String displayName,
      int startHour,
      int endHour,
      String level,
      String qualifyBasis,
      String qualifyComparison,
      BigDecimal qualifyThreshold,
      int sortOrder,
      boolean enabled,
      String description) {}

  /**
   * 把请求体校验成可写库的形态。
   *
   * <p>⚠️ 校验放在**服务层**（不是只靠 DB）：字段级的形状错误要给调用方一句人话，
   * 而不是让 DB 抛一句英文。
   */
  SlotInput validate(
      ReportingTimePatternModels.TimePatternSlotRequest request, String fallbackCode) {
    var code =
        request.slotCode() == null || request.slotCode().isBlank()
            ? fallbackCode
            : request.slotCode().trim();
    if (code.isEmpty()) {
      throw new IllegalArgumentException("时段码不能为空");
    }
    var name =
        request.displayName() == null || request.displayName().isBlank()
            ? code
            : request.displayName().trim();
    if (request.startHour() == null || request.endHour() == null) {
      throw new IllegalArgumentException("时段必须给出起止小时（startHour / endHour）");
    }
    var start = request.startHour();
    var end = request.endHour();
    if (start < 0 || start > 23) {
      throw new IllegalArgumentException("startHour 必须在 0..23 之间：" + start);
    }
    if (end < 1 || end > 24) {
      throw new IllegalArgumentException(
          "endHour 必须在 1..24 之间（左闭右开，24 表示到当天 23:59）：" + end);
    }
    if (end <= start) {
      throw new IllegalArgumentException(
          "时段区间必须是「左闭右开」的非空区间：endHour 必须大于 startHour（"
              + start
              + " → "
              + end
              + "）");
    }
    var level =
        request.level() == null || request.level().isBlank()
            ? ReportingTimePatternCatalog.Level.SHOULDER.name()
            : request.level().trim().toUpperCase();
    if (!List.of("PEAK", "SHOULDER", "TROUGH").contains(level)) {
      throw new IllegalArgumentException("时段层级只能是 PEAK / SHOULDER / TROUGH：" + request.level());
    }
    var basis =
        request.qualifyBasis() == null || request.qualifyBasis().isBlank()
            ? "ORDER_SHARE"
            : request.qualifyBasis().trim().toUpperCase();
    if (!List.of("ORDER_SHARE", "ORDER_COUNT").contains(basis)) {
      throw new IllegalArgumentException(
          "判定基准只能是 ORDER_SHARE / ORDER_COUNT：" + request.qualifyBasis());
    }
    var comparison =
        request.qualifyComparison() == null || request.qualifyComparison().isBlank()
            ? ("TROUGH".equals(level) ? "AT_MOST" : "AT_LEAST")
            : request.qualifyComparison().trim().toUpperCase();
    if (!List.of("AT_LEAST", "AT_MOST").contains(comparison)) {
      throw new IllegalArgumentException(
          "判定方向只能是 AT_LEAST / AT_MOST：" + request.qualifyComparison());
    }
    var threshold = request.qualifyThreshold() == null ? BigDecimal.ZERO : request.qualifyThreshold();
    if (threshold.signum() < 0) {
      throw new IllegalArgumentException("判定阈值不能为负：" + threshold);
    }
    if ("ORDER_SHARE".equals(basis) && threshold.compareTo(BigDecimal.ONE) > 0) {
      throw new IllegalArgumentException(
          "按订单占比判定时，阈值是 0..1 的小数（不是百分数）：" + threshold);
    }
    return new SlotInput(
        code,
        name,
        start,
        end,
        level,
        basis,
        comparison,
        threshold,
        request.sortOrder() == null ? 0 : request.sortOrder(),
        request.enabled() == null || request.enabled(),
        request.description() == null ? "" : request.description().trim());
  }

  /** 写一条时段配置。 */
  void writeSlot(UUID tenantId, SlotInput input) {
    sql.execute(
        """
        insert into reporting_time_pattern_slot
          (id,tenant_id,slot_code,display_name,start_hour,end_hour,level,qualify_basis,
           qualify_comparison,qualify_threshold,sort_order,enabled,description,created_at,updated_at)
        values (?,?,?,?,?,?,?,?,?,?,?,?,?,now(),now())
        on conflict (tenant_id, slot_code)
        do update set display_name = excluded.display_name,
                      start_hour = excluded.start_hour,
                      end_hour = excluded.end_hour,
                      level = excluded.level,
                      qualify_basis = excluded.qualify_basis,
                      qualify_comparison = excluded.qualify_comparison,
                      qualify_threshold = excluded.qualify_threshold,
                      sort_order = excluded.sort_order,
                      enabled = excluded.enabled,
                      description = excluded.description,
                      updated_at = now()
        """,
        Ids.next(),
        tenantId,
        input.slotCode(),
        input.displayName(),
        input.startHour(),
        input.endHour(),
        input.level(),
        input.qualifyBasis(),
        input.qualifyComparison(),
        input.qualifyThreshold(),
        input.sortOrder(),
        input.enabled(),
        input.description());
  }

  /** 触发延迟约束检查（把「时段配置非法」从提交期翻成调用方能读懂的错误）。 */
  void checkDeferredConstraints() {
    sql.execute("set constraints all immediate");
  }

  // ==========================================================================
  // 时段配置的增删改查
  // ==========================================================================

  /**
   * 🔴 **DB 钟域的「今天」**（{@code now()}），不是 JVM 的墙钟。
   *
   * <p>报表窗口的默认起止必须与落库时刻用**同一个钟域** ——
   * 本仓已经吃过三次「服务端判定所用的钟域」不一致的亏。
   */
  public LocalDate dbToday() {
    var row = sql.fetchOne("select (now() at time zone 'UTC')::date");
    return row == null ? LocalDate.now() : row.get(0, LocalDate.class);
  }

  /** 新建一个时段配置。 */
  public ReportingTimePatternModels.TimePatternSlotConfigView createSlot(
      UUID tenantId, ReportingTimePatternModels.TimePatternSlotRequest request) {
    var input = validate(request, "");
    return tenants.run(
        tenantId,
        () -> {
          writeSlot(tenantId, input);
          checkDeferredConstraints();
          return slotConfigView(tenantId, input.slotCode());
        });
  }

  /** 改一个时段配置。 */
  public ReportingTimePatternModels.TimePatternSlotConfigView updateSlot(
      UUID tenantId, String id, ReportingTimePatternModels.TimePatternSlotRequest request) {
    return tenants.run(
        tenantId,
        () -> {
          // ⚠️ **必须在租户事务里读**（FORCE RLS：事务外读出来是空集 ⇒ 「不存在」）。
          var existing = requireSlot(tenantId, id);
          var input = validate(request, existing.slotCode());
          sql.execute(
              """
              update reporting_time_pattern_slot
                 set slot_code = ?, display_name = ?, start_hour = ?, end_hour = ?, level = ?,
                     qualify_basis = ?, qualify_comparison = ?, qualify_threshold = ?,
                     sort_order = ?, enabled = ?, description = ?, updated_at = now()
               where id = ?::uuid and tenant_id = ?
              """,
              input.slotCode(),
              input.displayName(),
              input.startHour(),
              input.endHour(),
              input.level(),
              input.qualifyBasis(),
              input.qualifyComparison(),
              input.qualifyThreshold(),
              input.sortOrder(),
              input.enabled(),
              input.description(),
              existing.id(),
              tenantId);
          checkDeferredConstraints();
          return slotConfigView(tenantId, input.slotCode());
        });
  }

  /**
   * 整体替换时段配置（**一次成型**）。
   *
   * <p>⚠️ <b>先在 Java 里校验整套，再动库</b>：不合法就**一张表都不碰**；
   * 合法的才在一个事务里「先清空再重建」——
   * 中间态允许有空洞（延迟约束在提交时才检查），提交时那一套必须完整合法。
   */
  public List<ReportingTimePatternModels.TimePatternSlotConfigView> replaceSlots(
      UUID tenantId, ReportingTimePatternModels.TimePatternSlotReplaceRequest request) {
    var requests =
        request == null || request.slots() == null ? List.<ReportingTimePatternModels.TimePatternSlotRequest>of()
            : request.slots();
    if (requests.isEmpty()) {
      throw new IllegalArgumentException("整体替换必须给出**完整的**时段集合（至少一条）");
    }
    var inputs = new ArrayList<SlotInput>();
    for (var one : requests) {
      inputs.add(validate(one, ""));
    }
    validatePartition(inputs);
    return tenants.run(
        tenantId,
        () -> {
          sql.execute("delete from reporting_time_pattern_slot where tenant_id = ?", tenantId);
          for (var input : inputs) {
            writeSlot(tenantId, input);
          }
          checkDeferredConstraints();
          return slotConfigs(tenantId);
        });
  }

  /** 删一个时段配置。 */
  public void deleteSlot(UUID tenantId, String id) {
    tenants.run(
        tenantId,
        () -> {
          // ⚠️ **必须在租户事务里读**（FORCE RLS：事务外读出来是空集 ⇒ 「不存在」）。
          var existing = requireSlot(tenantId, id);
          sql.execute(
              "delete from reporting_time_pattern_slot where id = ?::uuid and tenant_id = ?",
              existing.id(),
              tenantId);
          // 🔴 删完可能**留了空洞** ⇒ 延迟约束在提交时会拒（整个删除回滚）。
          checkDeferredConstraints();
          return null;
        });
  }

  /** 取一条时段配置（取不到 ⇒ 404 形状的异常）。 */
  private SlotConfig requireSlot(UUID tenantId, String id) {
    UUID parsed;
    try {
      parsed = UUID.fromString(id);
    } catch (RuntimeException e) {
      throw new IllegalArgumentException("时段配置 id 不是合法的 UUID：" + id);
    }
    var row =
        sql.fetchOne(
            """
            select id, slot_code, display_name, start_hour, end_hour, level, qualify_basis,
                   qualify_comparison, qualify_threshold, sort_order, enabled, description
              from reporting_time_pattern_slot
             where id = ? and tenant_id = ?
            """,
            parsed,
            tenantId);
    if (row == null) {
      throw new IllegalArgumentException("时段配置不存在（不在这个租户名下）：" + id);
    }
    return new SlotConfig(
        row.get(0, UUID.class).toString(),
        row.get(1, String.class),
        row.get(2, String.class),
        row.get(3, Integer.class),
        row.get(4, Integer.class),
        row.get(5, String.class),
        row.get(6, String.class),
        row.get(7, String.class),
        row.get(8, BigDecimal.class),
        row.get(9, Integer.class),
        Boolean.TRUE.equals(row.get(10, Boolean.class)),
        row.get(11, String.class));
  }

  /** 一条时段配置的展示形态。 */
  private ReportingTimePatternModels.TimePatternSlotConfigView slotConfigView(
      UUID tenantId, String slotCode) {
    for (var view : slotConfigs(tenantId)) {
      if (view.slotCode().equals(slotCode)) return view;
    }
    throw new IllegalStateException("时段配置写进去了却读不出来：" + slotCode);
  }

  /**
   * 校验一整套时段配置：**互不重叠** + **完整覆盖 0..24 小时**。
   *
   * <p>⚠️ 这是数据层约束（延迟约束触发器）的**前置**版本 ——
   * 先在服务层说清楚哪里不合法，比让 DB 在提交时抛一句英文好读。
   */
  static void validatePartition(List<SlotInput> inputs) {
    var covered = new boolean[24];
    for (var input : inputs) {
      if (!input.enabled()) continue;
      for (int hour = input.startHour(); hour < input.endHour(); hour++) {
        if (covered[hour]) {
          throw new IllegalArgumentException(
              "时段区间重叠："
                  + input.slotCode()
                  + " 的 ["
                  + input.startHour()
                  + ","
                  + input.endHour()
                  + ") 与另一条时段的小时 "
                  + hour
                  + " 重叠（时段区间不可重叠）。");
        }
        covered[hour] = true;
      }
    }
    var missing = new ArrayList<Integer>();
    for (int hour = 0; hour < 24; hour++) {
      if (!covered[hour]) missing.add(hour);
    }
    if (!missing.isEmpty()) {
      throw new IllegalArgumentException(
          "启用时段必须**完整覆盖 0..24 小时**（不留空洞），当前缺 "
              + missing
              + "（小时 "
              + ReportingTimePatternCatalog.hourLabel(missing.get(0))
              + " 起）。");
    }
  }
}
