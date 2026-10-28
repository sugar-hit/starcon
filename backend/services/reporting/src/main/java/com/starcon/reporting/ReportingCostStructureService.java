package com.starcon.reporting;

import com.starcon.tenancy.Ids;
import com.starcon.tenancy.TenantTransactions;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Service;

/**
 * 13.4.2.1 成本结构的**读模型**（蓝图「输出」句：占比结构图 + 趋势 + 异常提示）。
 *
 * <p>⚠️ **本类不重算口径**：七类成本的金额来自上游算好的分摊 / 归集结果
 * （已落成 {@code reporting_cost_structure_fact}），本类只做**归桶、算占比、算基线、判异常**。
 *
 * <p>⚠️ **「算不出来」与「等于 0」必须分开**：占比、环比差、同比差、基线占比在算不出来时
 * 一律是 {@code null}（**不可比**），**绝不写成 0.00 / 0%**。
 *
 * <p>⚠️ **占比的分母口径必须写明**（{@code denominatorCaliber} / {@code denominatorNote}）：
 * 「占总成本」与「占收入」是两个不同的口径，混着报等于骗人。
 *
 * <p>⚠️ **基线来自真实历史投影数据**：前 N 期同口径占比（N = 该类成本的
 * {@code min_history_periods}），**不是**写死的基线常量。
 *
 * <p>⚠️ **多币种绝不相加**：每一行都带自己的币种，聚合键里始终带着币种。
 */
@Service
public class ReportingCostStructureService {

  /** 占比 / 差异率的标度（百分数，保留 4 位 —— 与既有指标口径一致）。 */
  private static final int RATE_SCALE = 4;

  /** 中间除法保留的位数（定标前先多留几位，避免除不尽时丢精度）。 */
  private static final int DIVIDE_SCALE = 8;

  /** 结构表最多返回多少行（超出时如实报截断，不静默丢行）。 */
  static final int MAX_ROWS = 500;

  /** 收入口径用的标准指标码（13.2.1.1 的标准口径，不在本模块重算）。 */
  static final String REVENUE_METRIC_CODE = "SALES_AMOUNT";

  /** 默认阈值口径（FIXED = 固定百分点）。 */
  static final String DEFAULT_THRESHOLD_MODE = "FIXED";

  /** 默认阈值取值（百分点）。 */
  static final BigDecimal DEFAULT_THRESHOLD_VALUE = new BigDecimal("5.000000");

  /** 默认最少历史期数（基线要前 N 期同口径占比才成立）。 */
  static final int DEFAULT_MIN_HISTORY_PERIODS = 3;

  /** 默认判定方向（BOTH = 升或降都算异常）。 */
  static final String DEFAULT_DIRECTION = "BOTH";

  private final DSLContext sql;
  private final TenantTransactions tenants;

  public ReportingCostStructureService(DSLContext sql, TenantTransactions tenants) {
    this.sql = sql;
    this.tenants = tenants;
  }

  // ==========================================================================
  // 主查询：占比结构 + 趋势 + 异常
  // ==========================================================================

  /**
   * 成本结构。
   *
   * @param granularity 期间粒度：{@code DAY} / {@code WEEK} / {@code MONTH}
   * @param dimension 归属维度：{@code ALL} / {@code SKU} / {@code SHOP} / {@code CATEGORY} /
   *     {@code PLATFORM}
   * @param denominatorCaliber 分母口径：{@code TOTAL_COST} / {@code REVENUE}
   * @param currencyFilter 只看某个币种（空 ⇒ 全部，**绝不跨币种相加**）
   * @param thresholdOverride 临时覆盖阈值取值（空 ⇒ 用租户自己的规则）
   * @param minHistoryOverride 临时覆盖最少历史期数（空 ⇒ 用租户自己的规则）
   * @param thresholdModeOverride 临时覆盖阈值口径（空 ⇒ 用租户自己的规则）
   */
  public ReportingCostStructureModels.CostStructureResponse structure(
      UUID tenantId,
      LocalDate from,
      LocalDate to,
      String granularity,
      String dimension,
      String denominatorCaliber,
      String currencyFilter,
      BigDecimal thresholdOverride,
      Integer minHistoryOverride,
      String thresholdModeOverride) {
    return tenants.run(
        tenantId,
        () -> {
          var rules = ensureRules(tenantId);
          var dimExpr = dimensionExpression(dimension);
          var revenueCaliber = "REVENUE".equals(denominatorCaliber);

          var maxHistory =
              rules.values().stream()
                  .mapToInt(rule -> rule.minHistoryPeriods())
                  .max()
                  .orElse(DEFAULT_MIN_HISTORY_PERIODS);
          if (minHistoryOverride != null && minHistoryOverride > 0) {
            maxHistory = Math.max(maxHistory, minHistoryOverride);
          }

          // 一次把「当前窗口 + 全部历史基线窗口 + 去年同期」需要的日粒度事实拉出来。
          // ⚠️ **去年同期也必须在跨度内** —— 否则同比基线恒为 null（而页面看不出是「没拉到」）。
          var spanFrom = from.minusDays((long) maxHistory * windowLength(from, to));
          if (from.minusYears(1).isBefore(spanFrom)) {
            spanFrom = from.minusYears(1);
          }
          var sums = daySums(tenantId, dimExpr, spanFrom, to, currencyFilter);
          var revenueDays = revenueDays(tenantId, spanFrom, to);

          var current = aggregate(sums, from, to);
          var currentRevenue = aggregateRevenue(revenueDays, from, to);

          var rows = new ArrayList<ReportingCostStructureModels.CostStructureRow>();
          var anomalies = new ArrayList<ReportingCostStructureModels.CostStructureAnomaly>();
          var missingCategories = new TreeSet<String>();

          for (var group : current.entrySet()) {
            var groupKey = group.getKey();
            var dimValue = group.getValue().dimValue();
            var currency = group.getValue().currency();
            var byCategory = group.getValue().amounts();
            var totalCost = BigDecimal.ZERO;
            for (var amount : byCategory.values()) {
              totalCost = totalCost.add(amount);
            }

            for (var entry : byCategory.entrySet()) {
              var category = entry.getKey();
              var amount = entry.getValue();
              var rule = rules.get(category);

              var denominator = revenueCaliber ? currentRevenue.get(currency) : totalCost;
              var share = shareOf(amount, denominator);
              var comparable = share != null;

              // 环比基线（前一期同长度窗口）、历史基线（前 N 期均值）、同比基线（去年同期）。
              var previousWindow = previousWindow(from, to);
              var previousShare =
                  shareOf(
                      categoryAmount(sums, groupKey, category, previousWindow),
                      denominatorOf(sums, revenueDays, groupKey, previousWindow, revenueCaliber));

              var requiredHistory = historyPeriods(rule, minHistoryOverride);
              var historyShares = new ArrayList<BigDecimal>();
              for (int k = 1; k <= requiredHistory; k++) {
                var window = historyWindow(from, to, k);
                var periodShare =
                    shareOf(
                        categoryAmount(sums, groupKey, category, window),
                        denominatorOf(sums, revenueDays, groupKey, window, revenueCaliber));
                if (periodShare != null) historyShares.add(periodShare);
              }
              var baselineShare =
                  !historyShares.isEmpty() && historyShares.size() >= requiredHistory
                      ? mean(historyShares)
                      : null;

              var yoyWindow = new Window(from.minusYears(1), to.minusYears(1));
              var yoyShare =
                  shareOf(
                      categoryAmount(sums, groupKey, category, yoyWindow),
                      denominatorOf(sums, revenueDays, groupKey, yoyWindow, revenueCaliber));

              var deltaVsPrevious =
                  (share == null || previousShare == null) ? null : share.subtract(previousShare);
              var deltaVsYoY = (share == null || yoyShare == null) ? null : share.subtract(yoyShare);

              var judgement =
                  judge(
                      rule,
                      share,
                      baselineShare,
                      historyShares,
                      thresholdOverride,
                      minHistoryOverride,
                      thresholdModeOverride);

              rows.add(
                  new ReportingCostStructureModels.CostStructureRow(
                      category,
                      label(category),
                      dimValue,
                      dimensionLabel(dimension, dimValue),
                      currency,
                      amount,
                      share,
                      denominator,
                      comparable,
                      previousShare,
                      baselineShare,
                      yoyShare,
                      deltaVsPrevious,
                      deltaVsYoY,
                      judgement.anomaly(),
                      judgement.reason(),
                      note(comparable, judgement.anomaly())));

              if (judgement.anomaly()) {
                anomalies.add(
                    new ReportingCostStructureModels.CostStructureAnomaly(
                        category,
                        label(category),
                        dimValue,
                        dimensionLabel(dimension, dimValue),
                        currency,
                        share,
                        baselineShare,
                        share.subtract(baselineShare),
                        judgement.direction(),
                        judgement.thresholdMode(),
                        judgement.thresholdValue(),
                        judgement.effectiveThreshold(),
                        judgement.reason(),
                        judgement.note()));
              }
            }
          }

          // 窗口里**完全没有事实**的成本类别：占比不可比（不是 0%）。
          for (var category : ReportingCostStructureModels.CostCategory.values()) {
            if (rows.stream().noneMatch(row -> row.costCategory().equals(category.name()))) {
              missingCategories.add(category.label());
            }
          }

          var trend = trend(sums, revenueDays, from, to, granularity, dimension, revenueCaliber);

          // ⚠️ 行数超上限时**如实报截断**（不静默丢行）。
          var truncated = rows.size() > MAX_ROWS;
          if (truncated) {
            rows = new ArrayList<>(rows.subList(0, MAX_ROWS));
          }
          anomalies.sort(
              Comparator.comparing(ReportingCostStructureModels.CostStructureAnomaly::costCategory)
                  .thenComparing(ReportingCostStructureModels.CostStructureAnomaly::dimensionKey)
                  .thenComparing(ReportingCostStructureModels.CostStructureAnomaly::currency));

          var warnings = new ArrayList<String>();
          if (truncated) {
            warnings.add(
                "成本结构表行数超过 " + MAX_ROWS + " 行，已截断（页面只显示前 " + MAX_ROWS + " 行）。");
          }
          if (!missingCategories.isEmpty()) {
            warnings.add(
                "窗口里没有事实的成本类别："
                    + String.join(" / ", missingCategories)
                    + " —— 它们的占比**不可比**（不是 0%）：可能是真没有这笔成本，也可能是上游还没投影。");
          }
          warnings.add(
              "占比的分母口径是「"
                  + denominatorLabel(denominatorCaliber)
                  + "」；换口径请改 denominatorCaliber 参数（两种口径不可混着读）。");
          warnings.add("报表是投影，数据更新时间取自投影落库时刻；与业务系统冲突时以业务系统为准。");

          return new ReportingCostStructureModels.CostStructureResponse(
              from,
              to,
              granularity,
              dimension,
              denominatorCaliber,
              denominatorNote(denominatorCaliber, dimension),
              rows,
              trend,
              anomalies,
              dataUpdatedAt(tenantId),
              "最近一次成本事实投影落库时刻（reporting_cost_structure_fact 的 updated_at 最大值）。",
              warnings,
              caliberNote());
        });
  }

  // ==========================================================================
  // 阈值规则
  // ==========================================================================

  /** 阈值规则（租户自己的口径）。 */
  public record Rule(
      String costCategory,
      String thresholdMode,
      BigDecimal thresholdValue,
      int minHistoryPeriods,
      String direction,
      boolean enabled) {}

  /**
   * 列出阈值规则（**先播种默认值**再读）。
   *
   * <p>⚠️ **播种的是默认值，不是「算出来的阈值」**：真正的阈值随租户改（接口上可改），
   * 也可随历史波动自动伸缩（STDDEV 口径）。
   */
  public List<ReportingCostStructureModels.CostStructureRule> rules(UUID tenantId) {
    return tenants.run(
        tenantId,
        () -> {
          var rules = ensureRules(tenantId);
          var out = new ArrayList<ReportingCostStructureModels.CostStructureRule>();
          for (var category : ReportingCostStructureModels.CostCategory.values()) {
            var rule = rules.get(category.name());
            if (rule == null) continue;
            out.add(
                new ReportingCostStructureModels.CostStructureRule(
                    category.name(),
                    category.label(),
                    rule.thresholdMode(),
                    rule.thresholdValue(),
                    rule.minHistoryPeriods(),
                    rule.direction(),
                    rule.enabled(),
                    ruleUpdatedAt(tenantId, category.name())));
          }
          return out;
        });
  }

  /**
   * 改阈值（只改写进来的那几个字段）。
   *
   * <p>⚠️ **本模块只改自己的规则表**，不写任何上游的口径（费率规则在 catalog、
   * 归集规则在 finance —— 跨服务写入被 AGENTS.md 禁止）。
   */
  public ReportingCostStructureModels.CostStructureRule updateRule(
      UUID tenantId,
      String costCategory,
      ReportingCostStructureModels.CostStructureRuleInput input) {
    var category = ReportingCostStructureModels.CostCategory.parse(costCategory);
    if (category == null) {
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.BAD_REQUEST,
          "costCategory 只能是 "
              + java.util.Arrays.stream(ReportingCostStructureModels.CostCategory.values())
                  .map(Enum::name)
                  .toList()
              + "，收到："
              + costCategory);
    }
    return tenants.run(
        tenantId,
        () -> {
          ensureRules(tenantId);
          var current = readRule(tenantId, category.name());
          var mode =
              input.thresholdMode() == null || input.thresholdMode().isBlank()
                  ? current.thresholdMode()
                  : resolveThresholdMode(input.thresholdMode());
          var value =
              input.thresholdValue() == null ? current.thresholdValue() : input.thresholdValue();
          if (value.signum() < 0) {
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST, "阈值不能是负数");
          }
          var minHistory =
              input.minHistoryPeriods() == null ? current.minHistoryPeriods() : input.minHistoryPeriods();
          if (minHistory < 1) {
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST, "最少历史期数至少为 1");
          }
          var direction =
              input.direction() == null || input.direction().isBlank()
                  ? current.direction()
                  : resolveDirection(input.direction());
          var enabled = input.enabled() == null ? current.enabled() : input.enabled();

          sql.execute(
              """
              insert into reporting_cost_structure_rule
                (id,tenant_id,cost_category,threshold_mode,threshold_value,
                 min_history_periods,direction,enabled,updated_at)
              values (?,?,?,?,?,?,?,?,now())
              on conflict (tenant_id,cost_category)
              do update set threshold_mode = excluded.threshold_mode,
                            threshold_value = excluded.threshold_value,
                            min_history_periods = excluded.min_history_periods,
                            direction = excluded.direction,
                            enabled = excluded.enabled,
                            updated_at = now()
              """,
              Ids.next(),
              tenantId,
              category.name(),
              mode,
              value,
              minHistory,
              direction,
              enabled);
          return new ReportingCostStructureModels.CostStructureRule(
              category.name(),
              category.label(),
              mode,
              value,
              minHistory,
              direction,
              enabled,
              ruleUpdatedAt(tenantId, category.name()));
        });
  }

  /** 播种默认规则（幂等：已存在的行不动）。 */
  private Map<String, Rule> ensureRules(UUID tenantId) {
    for (var category : ReportingCostStructureModels.CostCategory.values()) {
      sql.execute(
          """
          insert into reporting_cost_structure_rule
            (id,tenant_id,cost_category,threshold_mode,threshold_value,
             min_history_periods,direction,enabled,updated_at)
          values (?,?,?,?,?,?,?,?,now())
          on conflict (tenant_id,cost_category) do nothing
          """,
          Ids.next(),
          tenantId,
          category.name(),
          DEFAULT_THRESHOLD_MODE,
          DEFAULT_THRESHOLD_VALUE,
          DEFAULT_MIN_HISTORY_PERIODS,
          DEFAULT_DIRECTION,
          true);
    }
    var out = new LinkedHashMap<String, Rule>();
    var rows =
        sql.fetch(
            """
            select cost_category, threshold_mode, threshold_value,
                   min_history_periods, direction, enabled
              from reporting_cost_structure_rule
             where tenant_id = ?
             order by cost_category
            """,
            tenantId);
    for (var row : rows) {
      out.put(
          row.get(0, String.class),
          new Rule(
              row.get(0, String.class),
              row.get(1, String.class),
              row.get(2, BigDecimal.class),
              row.get(3, Integer.class),
              row.get(4, String.class),
              row.get(5, Boolean.class)));
    }
    return out;
  }

  /** 读一条规则（不存在 ⇒ 默认值，保证调用方拿到的一定是可用值）。 */
  private Rule readRule(UUID tenantId, String costCategory) {
    var row =
        sql.fetchOne(
            """
            select threshold_mode, threshold_value, min_history_periods, direction, enabled
              from reporting_cost_structure_rule
             where tenant_id = ? and cost_category = ?
            """,
            tenantId,
            costCategory);
    if (row == null) {
      return new Rule(
          costCategory,
          DEFAULT_THRESHOLD_MODE,
          DEFAULT_THRESHOLD_VALUE,
          DEFAULT_MIN_HISTORY_PERIODS,
          DEFAULT_DIRECTION,
          true);
    }
    return new Rule(
        costCategory,
        row.get(0, String.class),
        row.get(1, BigDecimal.class),
        row.get(2, Integer.class),
        row.get(3, String.class),
        row.get(4, Boolean.class));
  }

  /** 规则的最后修改时刻（没有 ⇒ null）。 */
  private OffsetDateTime ruleUpdatedAt(UUID tenantId, String costCategory) {
    var row =
        sql.fetchOne(
            """
            select max(updated_at) from reporting_cost_structure_rule
             where tenant_id = ? and cost_category = ?
            """,
            tenantId,
            costCategory);
    return row == null ? null : row.get(0, OffsetDateTime.class);
  }

  // ==========================================================================
  // 异常判定
  // ==========================================================================

  /** 一次判定的结果（异常与否 + 为什么）。 */
  private record Judgement(
      boolean anomaly,
      String reason,
      String direction,
      String thresholdMode,
      BigDecimal thresholdValue,
      BigDecimal effectiveThreshold,
      String note) {}

  /**
   * 判异常。
   *
   * <p>⚠️ **阈值不是写死的百分比**：口径来自租户自己的规则表（{@code FIXED} 固定百分点 /
   * {@code STDDEV} 基线标准差的若干倍），也可以在一次查询里临时覆盖。
   *
   * <p>⚠️ **判不出来就说判不出来**：基线算不出来、历史期数不足、标准差为 0 ——
   * 这三种情况下异常是 **false + 一句原因**，**不是**「没异常」。
   */
  private Judgement judge(
      Rule rule,
      BigDecimal share,
      BigDecimal baselineShare,
      List<BigDecimal> historyShares,
      BigDecimal thresholdOverride,
      Integer minHistoryOverride,
      String thresholdModeOverride) {
    if (rule == null) {
      return new Judgement(
          false,
          "这一类成本还没有配置阈值规则，判不了异常。",
          DEFAULT_DIRECTION,
          DEFAULT_THRESHOLD_MODE,
          DEFAULT_THRESHOLD_VALUE,
          null,
          "没有规则就不判 —— 「没报异常」不等于「结构正常」。");
    }
    if (!rule.enabled()) {
      return new Judgement(
          false,
          "这一类成本的阈值规则已停用，不判异常。",
          rule.direction(),
          rule.thresholdMode(),
          rule.thresholdValue(),
          null,
          "规则停用是租户自己的决定。");
    }
    var mode =
        thresholdModeOverride == null || thresholdModeOverride.isBlank()
            ? rule.thresholdMode()
            : resolveThresholdMode(thresholdModeOverride);
    var threshold = thresholdOverride == null ? rule.thresholdValue() : thresholdOverride;
    if (share == null) {
      return new Judgement(
          false,
          "当期占比算不出来（分母缺失或为 0），判不了异常。",
          rule.direction(),
          mode,
          threshold,
          null,
          "占比不可比时不做判定 —— 拿「算不出来」当「没异常」就是在骗人。");
    }
    var requiredHistory = historyPeriods(rule, minHistoryOverride);
    if (baselineShare == null) {
      return new Judgement(
          false,
          "历史基线算不出来（可比的历史期数不足 "
              + historyShares.size()
              + " 期，规则要求 "
              + requiredHistory
              + " 期），判不了异常。",
          rule.direction(),
          mode,
          threshold,
          null,
          "基线要**前 N 期**同口径占比才成立；历史不够就如实说不够。");
    }
    var delta = share.subtract(baselineShare);
    BigDecimal effective;
    if ("STDDEV".equals(mode)) {
      if (historyShares.size() < 2) {
        return new Judgement(
            false,
            "标准差口径至少要 2 期历史占比才判得了异常（当前 " + historyShares.size() + " 期）。",
            rule.direction(),
            mode,
            threshold,
            null,
            "标准差口径判不出来时如实报「判不了」，不退回固定阈值。");
      }
      var deviation = standardDeviation(historyShares);
      if (deviation == null || deviation.signum() == 0) {
        return new Judgement(
            false,
            "基线占比没有波动（标准差为 0），标准差口径判不了异常。",
            rule.direction(),
            mode,
            threshold,
            null,
            "标准差为 0 时任何阈值都退化成「一切都是异常」⇒ 如实报判不了。");
      }
      effective = threshold.multiply(deviation).setScale(RATE_SCALE, RoundingMode.HALF_UP);
    } else {
      effective = threshold;
    }
    var direction = rule.direction();
    var hit =
        switch (direction) {
          case "UP" -> delta.signum() >= 0 && delta.abs().compareTo(effective) >= 0;
          case "DOWN" -> delta.signum() < 0 && delta.abs().compareTo(effective) >= 0;
          default -> delta.abs().compareTo(effective) >= 0;
        };
    if (!hit) {
      return new Judgement(
          false,
          "占比变化 "
              + delta.setScale(RATE_SCALE, RoundingMode.HALF_UP).toPlainString()
              + " 个百分点，未达阈值 "
              + effective.setScale(RATE_SCALE, RoundingMode.HALF_UP).toPlainString()
              + " 个百分点。",
          direction,
          mode,
          threshold,
          effective,
          "未达阈值时如实报「未达」，不报「异常」。");
    }
    return new Judgement(
        true,
        "占比 "
            + share.setScale(RATE_SCALE, RoundingMode.HALF_UP).toPlainString()
            + "% 相对基线 "
            + baselineShare.setScale(RATE_SCALE, RoundingMode.HALF_UP).toPlainString()
            + "% 变化 "
            + delta.setScale(RATE_SCALE, RoundingMode.HALF_UP).toPlainString()
            + " 个百分点，达到阈值 "
            + effective.setScale(RATE_SCALE, RoundingMode.HALF_UP).toPlainString()
            + " 个百分点（阈值口径："
            + thresholdModeLabel(mode)
            + "）。",
        direction,
        mode,
        threshold,
        effective,
        "蓝图关系句：「**成本结构变化比绝对值更有信号**：广告占比突然上升往往先于利润下滑发生」—— "
            + "所以占比**上升**（direction=UP）也可以被单独盯。");
  }

  // ==========================================================================
  // 取数
  // ==========================================================================

  /** 日粒度的成本事实汇总。 */
  private record DaySum(
      String costCategory, String currency, String dimValue, LocalDate periodStart, BigDecimal amount) {}

  /** 一个聚合组（维度取值 × 币种）。 */
  private record Group(String dimValue, String currency, Map<String, BigDecimal> amounts) {}


  /**
   * 拉日粒度汇总（一次拉够当前窗口 + 全部基线窗口 + 去年同期）。
   *
   * <p>⚠️ **按 {@code period_start} 落在窗口内归属**（跨期事实不拆分、不平移）。
   */
  private List<DaySum> daySums(
      UUID tenantId, String dimExpr, LocalDate spanFrom, LocalDate spanTo, String currencyFilter) {
    var currencyClause =
        currencyFilter == null || currencyFilter.isBlank() ? "" : " and currency = ?";
    var params = new ArrayList<Object>();
    params.add(tenantId);
    params.add(spanFrom);
    params.add(spanTo);
    if (currencyFilter != null && !currencyFilter.isBlank()) params.add(currencyFilter);
    // ⚠️ **维度表达式是白名单常量**（dimensionExpression 只有五种取值），拼进 SQL 安全。
    var statement =
        "select cost_category, currency, "
            + dimExpr
            + " as dim_value, period_start, sum(amount) as amount\n"
            + "  from reporting_cost_structure_fact\n"
            + " where tenant_id = ? and period_start >= ? and period_start <= ?"
            + currencyClause
            + "\n group by cost_category, currency, "
            + dimExpr
            + ", period_start\n"
            + " order by dim_value, currency, cost_category, period_start";
    var rows = sql.fetch(statement, params.toArray());
    var out = new ArrayList<DaySum>();
    for (var row : rows) {
      out.add(
          new DaySum(
              row.get(0, String.class),
              row.get(1, String.class),
              row.get(2, String.class),
              row.get(3, LocalDate.class),
              row.get(4, BigDecimal.class)));
    }
    return out;
  }

  /** 日粒度的销售额（收入口径的分母；**只读标准指标口径，不重算**）。 */
  private record RevenueDay(LocalDate bucketDate, String currency, BigDecimal value) {}

  private List<RevenueDay> revenueDays(UUID tenantId, LocalDate spanFrom, LocalDate spanTo) {
    var rows =
        sql.fetch(
            """
            select bucket_date, currency, sum(value) as value
              from reporting_metric_fact
             where tenant_id = ? and metric_code = ? and scope_type = 'COMPANY'
               and bucket_date >= ? and bucket_date <= ?
             group by bucket_date, currency
             order by bucket_date, currency
            """,
            tenantId,
            REVENUE_METRIC_CODE,
            spanFrom,
            spanTo);
    var out = new ArrayList<RevenueDay>();
    for (var row : rows) {
      out.add(
          new RevenueDay(
              row.get(0, LocalDate.class), row.get(1, String.class), row.get(2, BigDecimal.class)));
    }
    return out;
  }

  /** 窗口内的「维度取值 × 币种 → 成本类别 → 金额」。 */
  private Map<GroupKey, Group> aggregate(List<DaySum> sums, LocalDate from, LocalDate to) {
    var out = new LinkedHashMap<GroupKey, Group>();
    for (var sum : sums) {
      if (sum.periodStart().isBefore(from) || sum.periodStart().isAfter(to)) continue;
      var key = new GroupKey(sum.dimValue(), sum.currency());
      var group = out.get(key);
      if (group == null) {
        group = new Group(sum.dimValue(), sum.currency(), new LinkedHashMap<String, BigDecimal>());
        out.put(key, group);
      }
      group.amounts().merge(sum.costCategory(), sum.amount(), BigDecimal::add);
    }
    return out;
  }

  /** 窗口内的销售额（按币种；**绝不跨币种相加**）。 */
  private Map<String, BigDecimal> aggregateRevenue(
      List<RevenueDay> days, LocalDate from, LocalDate to) {
    var out = new LinkedHashMap<String, BigDecimal>();
    for (var day : days) {
      if (day.bucketDate().isBefore(from) || day.bucketDate().isAfter(to)) continue;
      out.merge(day.currency(), day.value(), BigDecimal::add);
    }
    return out;
  }

  /** 某一聚合组下某一成本类别在窗口内的金额。 */
  private BigDecimal categoryAmount(
      List<DaySum> sums, GroupKey groupKey, String costCategory, Window window) {
    BigDecimal total = null;
    for (var sum : sums) {
      if (!new GroupKey(sum.dimValue(), sum.currency()).equals(groupKey)) continue;
      if (!sum.costCategory().equals(costCategory)) continue;
      if (sum.periodStart().isBefore(window.from()) || sum.periodStart().isAfter(window.to())) continue;
      total = total == null ? sum.amount() : total.add(sum.amount());
    }
    return total;
  }

  /**
   * 某一聚合组的分母。
   *
   * <p>⚠️ **总成本口径**的分母是该组的七类成本之和；**收入口径**的分母是公司口径销售额
   * （**与维度取值无关** —— 该维度没有独立的收入事实）。
   */
  private BigDecimal denominatorOf(
      List<DaySum> sums,
      List<RevenueDay> revenueDays,
      GroupKey groupKey,
      Window window,
      boolean revenueCaliber) {
    if (revenueCaliber) {
      BigDecimal total = null;
      for (var day : revenueDays) {
        if (!day.currency().equals(groupKey.currency())) continue;
        if (day.bucketDate().isBefore(window.from()) || day.bucketDate().isAfter(window.to())) continue;
        total = total == null ? day.value() : total.add(day.value());
      }
      return total;
    }
    BigDecimal total = null;
    for (var sum : sums) {
      if (!new GroupKey(sum.dimValue(), sum.currency()).equals(groupKey)) continue;
      if (sum.periodStart().isBefore(window.from()) || sum.periodStart().isAfter(window.to())) continue;
      total = total == null ? sum.amount() : total.add(sum.amount());
    }
    return total;
  }

  // ==========================================================================
  // 趋势
  // ==========================================================================

  /** 趋势的分组键（维度取值 × 币种 × 期间桶）。 */
  private record TrendKey(String dimValue, String currency, LocalDate bucket) {}

  /**
   * 占比趋势（按期间粒度分桶）。
   *
   * <p>⚠️ **分母算不出来的期间没有点**（不是 0%）：那一段是「算不出来」还是「真的是 0」，
   * 在页面上必须分开。
   */
  private List<ReportingCostStructureModels.CostStructureTrendPoint> trend(
      List<DaySum> sums,
      List<RevenueDay> revenueDays,
      LocalDate from,
      LocalDate to,
      String granularity,
      String dimension,
      boolean revenueCaliber) {
    var amounts = new LinkedHashMap<TrendKey, Map<String, BigDecimal>>();
    var totals = new LinkedHashMap<TrendKey, BigDecimal>();
    var revenueTotals = new LinkedHashMap<TrendKey, BigDecimal>();

    for (var sum : sums) {
      if (sum.periodStart().isBefore(from) || sum.periodStart().isAfter(to)) continue;
      var key = new TrendKey(sum.dimValue(), sum.currency(), bucket(sum.periodStart(), granularity));
      var byCategory = amounts.computeIfAbsent(key, k -> new LinkedHashMap<String, BigDecimal>());
      byCategory.merge(sum.costCategory(), sum.amount(), BigDecimal::add);
      totals.merge(key, sum.amount(), BigDecimal::add);
    }
    for (var day : revenueDays) {
      if (day.bucketDate().isBefore(from) || day.bucketDate().isAfter(to)) continue;
      var key = new TrendKey("", day.currency(), bucket(day.bucketDate(), granularity));
      revenueTotals.merge(key, day.value(), BigDecimal::add);
    }

    var out = new ArrayList<ReportingCostStructureModels.CostStructureTrendPoint>();
    for (var entry : amounts.entrySet()) {
      var key = entry.getKey();
      var byCategory = entry.getValue();
      var denominator =
          revenueCaliber
              ? revenueTotals.get(new TrendKey("", key.currency(), key.bucket()))
              : totals.get(key);
      for (var categoryEntry : byCategory.entrySet()) {
        var amount = categoryEntry.getValue();
        var share = shareOf(amount, denominator);
        out.add(
            new ReportingCostStructureModels.CostStructureTrendPoint(
                key.bucket(),
                key.bucket(),
                categoryEntry.getKey(),
                label(categoryEntry.getKey()),
                key.dimValue(),
                dimensionLabel(dimension, key.dimValue()),
                key.currency(),
                amount,
                share,
                denominator,
                share != null));
      }
    }
    out.sort(
        Comparator.comparing(ReportingCostStructureModels.CostStructureTrendPoint::periodStart)
            .thenComparing(ReportingCostStructureModels.CostStructureTrendPoint::costCategory)
            .thenComparing(ReportingCostStructureModels.CostStructureTrendPoint::dimensionKey)
            .thenComparing(ReportingCostStructureModels.CostStructureTrendPoint::currency));
    return out;
  }

  // ==========================================================================
  // 能力声明
  // ==========================================================================

  /** 能力声明：哪些上游真接通、哪些确实拿不到、归谁。 */
  public ReportingCostStructureModels.CostStructureCapabilities capabilities() {
    var categories = new ArrayList<String>();
    for (var category : ReportingCostStructureModels.CostCategory.values()) {
      categories.add(category.label());
    }
    return new ReportingCostStructureModels.CostStructureCapabilities(
        true,
        categories,
        List.of(
            "12.4.1.2 成本计算（finance：销售出库成本 ⇒ 采购成本）",
            "5.2.1.3 成本分摊（catalog：逐 SKU 头程分摊额 ⇒ 头程分摊）",
            "5.2.3.1 平台费用（catalog：实际费用台账 ⇒ 平台费）",
            "5.2.3.2 物流与头程费用（catalog：实际运费台账 ⇒ 物流费）",
            "12.3.1.2 交易明细（finance：佣金 / FBA 费用 / 广告费）",
            "11.1.1.1 售后单列表（trade：退货 ⇒ 退货成本）",
            "12.4.2.1 费用归集（finance：订阅 / 人员 / 办公 ⇒ 其它）",
            "03 商品档案（catalog：SKU → 类目映射）"),
        List.of(
            "**平台费与物流费的 SKU 级结构算不出来**：上游实际费用台账"
                + "（platform_fee_actual / logistics_freight_actual）只带平台 / 站点 / 渠道维度，"
                + "**不带 SKU**；12.3.1.2 结算明细只到店铺级 ⇒ 这两类费用在 SKU / 类目维度上只覆盖一部分。",
            "**退货没有店铺与 SKU 维度**：11.1.1.1 售后单给的是金额 / 数量 / 受理时刻，"
                + "**不带店铺、不带 SKU** ⇒ 退货成本只能落到「全部」这一层。",
            "**头程分摊按分摊生效日归属**：上游只给生效日、不给分摊期间终点 ⇒ "
                + "只在**生效日落在窗口内**时计入，跨期分摊不拆分、也不向后平移。",
            "**收入口径的分母是公司口径销售额**：13.2.1.1 的 SALES_AMOUNT（COMPANY 范围），"
                + "**不是**所选维度自己的收入（该维度没有独立收入事实）⇒ "
                + "按 SKU / 店铺 / 类目看时，分母仍是公司销售额。",
            "**类目是本模块补的维度**：上游成本事实里没有类目，类目由 catalog 的 SKU 档案映射；"
                + "映射不到就留空（**不猜一个类目**）。",
            "**上游的推事件**：上游尚未产出成本结构型 outbox 事件 ⇒ 现在的事实来自 "
                + "POST /reporting/cost-structure/refresh 的拉取路径（推路径已就绪，与拉共用同一套幂等键）。"),
        DEFAULT_THRESHOLD_MODE,
        DEFAULT_THRESHOLD_VALUE,
        DEFAULT_MIN_HISTORY_PERIODS,
        "阈值**不是写死的百分比**：它落在租户自己的规则表里（固定百分点 / 基线标准差的若干倍两种口径），"
            + "可在 PUT /reporting/cost-structure/rules/{costCategory} 上按成本类别改。");
  }

  // ==========================================================================
  // 工具
  // ==========================================================================

  /** 一次窗口。 */
  private record Window(LocalDate from, LocalDate to) {}

  /** 聚合键（维度取值 × 币种）。 */
  private record GroupKey(String dimValue, String currency) {}

  /** 窗口长度（天）。 */
  private static long windowLength(LocalDate from, LocalDate to) {
    return ChronoUnit.DAYS.between(from, to) + 1;
  }

  /** 环比窗口（紧邻当前窗口的同长度上一段）。 */
  private static Window previousWindow(LocalDate from, LocalDate to) {
    var length = windowLength(from, to);
    return new Window(from.minusDays(length), from.minusDays(1));
  }

  /** 历史基线窗口（第 k 期，k = 1 是紧邻当前窗口的那一段）。 */
  private static Window historyWindow(LocalDate from, LocalDate to, int k) {
    var length = windowLength(from, to);
    return new Window(from.minusDays(k * length), from.minusDays((k - 1) * length + 1));
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

  /** 占比（百分数）。**分母缺失或为 0 ⇒ null（不可比），不是 0%。 */
  private static BigDecimal shareOf(BigDecimal amount, BigDecimal denominator) {
    if (amount == null || denominator == null || denominator.signum() == 0) return null;
    return amount
        .multiply(new BigDecimal("100"))
        .divide(denominator, DIVIDE_SCALE, RoundingMode.HALF_UP)
        .setScale(RATE_SCALE, RoundingMode.HALF_UP);
  }

  /** 均值。 */
  private static BigDecimal mean(List<BigDecimal> values) {
    if (values.isEmpty()) return null;
    var sum = BigDecimal.ZERO;
    for (var value : values) {
      sum = sum.add(value);
    }
    return sum.divide(new BigDecimal(values.size()), DIVIDE_SCALE, RoundingMode.HALF_UP)
        .setScale(RATE_SCALE, RoundingMode.HALF_UP);
  }

  /** 样本标准差（除以 n − 1；n ≥ 2 才有意义）。 */
  private static BigDecimal standardDeviation(List<BigDecimal> values) {
    if (values.size() < 2) return null;
    var average = mean(values);
    var sum = BigDecimal.ZERO;
    for (var value : values) {
      var delta = value.subtract(average);
      sum = sum.add(delta.multiply(delta));
    }
    var variance = sum.divide(new BigDecimal(values.size() - 1), DIVIDE_SCALE, RoundingMode.HALF_UP);
    return new BigDecimal(variance.sqrt(new MathContext(16)).toPlainString())
        .setScale(RATE_SCALE, RoundingMode.HALF_UP);
  }

  /** 维度表达式（**白名单拼进 SQL**，不接受外部字符串）。 */
  static String dimensionExpression(String dimension) {
    return switch (dimension) {
      case "SKU" -> "sku_code";
      case "SHOP" -> "shop_code";
      case "CATEGORY" -> "category_code";
      case "PLATFORM" -> "platform_code";
      default -> "''::text";
    };
  }

  /** 维度取值的中文说明。 */
  static String dimensionLabel(String dimension, String dimValue) {
    if (dimValue == null || dimValue.isBlank()) {
      return switch (dimension) {
        case "SKU" -> "未归属 SKU";
        case "SHOP" -> "未归属店铺";
        case "CATEGORY" -> "未归类";
        case "PLATFORM" -> "未归属平台";
        default -> "全部";
      };
    }
    return switch (dimension) {
      case "SKU" -> "SKU " + dimValue;
      case "SHOP" -> "店铺 " + dimValue;
      case "CATEGORY" -> "类目 " + dimValue;
      case "PLATFORM" -> "平台 " + dimValue;
      default -> "全部";
    };
  }

  /** 成本类别的中文名（不认识就原样返回，不编一个名字）。 */
  private static String label(String costCategory) {
    var parsed = ReportingCostStructureModels.CostCategory.parse(costCategory);
    return parsed == null ? costCategory : parsed.label();
  }

  /** 分母口径的中文名。 */
  private static String denominatorLabel(String denominatorCaliber) {
    return "REVENUE".equals(denominatorCaliber) ? "收入口径（占销售额）" : "总成本口径（占成本合计）";
  }

  /** 分母口径的逐字说明（页面上原样显示）。 */
  private static String denominatorNote(String denominatorCaliber, String dimension) {
    if ("REVENUE".equals(denominatorCaliber)) {
      return "占比 = 该类成本 ÷ **公司口径销售额**（13.2.1.1 的 SALES_AMOUNT / COMPANY 范围）。"
          + (dimension == null || "ALL".equals(dimension)
              ? "当前是全局口径，分母与维度取值一致。"
              : "当前按「"
                  + dimension
                  + "」维度拆解，但**分母仍是公司口径销售额** —— 该维度没有独立的收入事实，"
                      + "所以这里的占比读作「这一维度吃掉了公司销售额的多大比例」。");
    }
    return "占比 = 该类成本 ÷ **同一维度取值下的成本合计**（七类成本之和，**不含**收入，也不跨币种相加）。";
  }

  /** 口径说明（七类成本分别来自哪个上游）。 */
  private static String caliberNote() {
    return "采购成本：12.4.1.2 成本计算的销售出库成本分录（按出库日归属，含「有缺口」的部分）。"
        + "头程分摊：5.2.1.3 成本分摊的计算分摊额（按分摊生效日归属，不含人工微调）。"
        + "平台费：5.2.3.1 实际费用台账 + 12.3.1.2 佣金。"
        + "物流费：5.2.3.2 实际运费台账 + 12.3.1.2 FBA 费用。"
        + "广告费：12.3.1.2 广告费明细。"
        + "退货：11.1.1.1 售后单的退货金额（按受理时刻归属）。"
        + "其它：12.4.2.1 费用归集条目（订阅费 / 人员费用 / 办公费用）。"
        + "跨币种绝不相加。";
  }

  /** 阈值口径的中文名。 */
  private static String thresholdModeLabel(String mode) {
    return "STDDEV".equals(mode) ? "基线标准差的若干倍" : "固定百分点";
  }

  /** 阈值口径解析（非法取值给一句能直接展示的话）。 */
  static String resolveThresholdMode(String raw) {
    var value = raw.trim().toUpperCase();
    if (!List.of("FIXED", "STDDEV").contains(value)) {
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.BAD_REQUEST,
          "thresholdMode 只能是 FIXED / STDDEV，收到：" + raw);
    }
    return value;
  }

  /** 判定方向解析。 */
  static String resolveDirection(String raw) {
    var value = raw.trim().toUpperCase();
    if (!List.of("BOTH", "UP", "DOWN").contains(value)) {
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.BAD_REQUEST,
          "direction 只能是 BOTH / UP / DOWN，收到：" + raw);
    }
    return value;
  }

  /** 规则里的历史期数（可被一次查询临时覆盖）。 */
  private static int historyPeriods(Rule rule, Integer override) {
    if (override != null && override > 0) return override;
    return rule == null ? DEFAULT_MIN_HISTORY_PERIODS : rule.minHistoryPeriods();
  }

  /** 这一行为什么算不出来（或为什么可比）。 */
  private static String note(boolean comparable, boolean anomaly) {
    if (!comparable) {
      return "占比算不出来（分母缺失或为 0）—— **不是 0%**。";
    }
    return anomaly ? "占比已判为异常（见异常提示）。" : "占比可比。";
  }

  /**
   * 数据更新时间（成本事实表的最大落库时刻）。
   *
   * <p>⚠️ **表空 ⇒ null**，不是 {@code -infinity}：用 {@code -infinity} 当哨兵时，
   * 空表会算出一个「比公元无限还早」的时间戳，页面上会显示成「数据截至 -infinity」——
   * 那是一个长得像时间的假值。宁可给 null（页面上写「尚未投影任何成本事实」）。
   */
  private OffsetDateTime dataUpdatedAt(UUID tenantId) {
    var row =
        sql.fetchOne(
            "select max(updated_at) from reporting_cost_structure_fact where tenant_id = ?",
            tenantId);
    return row == null ? null : row.get(0, OffsetDateTime.class);
  }
}
