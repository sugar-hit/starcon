package com.starcon.channel;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 16.1.1.2 绩效指标监控的<b>规则层</b>：指标口径、阈值解析、预警判定、跌破预测。
 *
 * <p>本类不碰数据库、不碰 HTTP，**可单测**（与 {@link AccountHealthRules}、
 * {@link CompliancePrecheckRules} 同一手法）。
 *
 * <p>🔴🔴 本类守的是五件事：
 *
 * <ol>
 *   <li><b>双来源如实表达</b>：四项指标的时间序列来自 16.1.1.1 的
 *       {@code account_health_metric}，响应时间 / 账户评级来自本模块自有的
 *       {@code account_health_performance_metric} —— 两个来源**不假装是同一个**。</li>
 *   <li><b>量不到 ≠ 0</b>：{@code value=null} 是「这一项我们没测到」，
 *       <b>不进已知数</b>，也<b>不算通过</b>。</li>
 *   <li><b>量纲不可混</b>：比值（0..1）、小时（0..8760）、星级（0..5）是三种不同的量，
 *       阈值与判定都带着 {@code unit}，不会出现「用比值的阈值去比小时」。</li>
 *   <li><b>阈值在服务端</b>：预警阈值解析顺序是「本模块自定义 &gt; 上游租户覆盖 &gt;
 *       内置默认」，每一档都带得出<b>用的是哪条阈值</b>。</li>
 *   <li><b>预测不许造假</b>：点数不足就如实说「无法预测」并给原因，
 *       <b>绝不给一个假预测</b>。</li>
 * </ol>
 *
 * <p>🔴 <b>为什么默认阈值不是「平台口径」</b>：各平台的绩效口径并不通用
 * （Amazon 的 ODR 与其它平台的缺陷率定义不同，账户评级体系更是千差万别）。
 * 本模块用一组<b>写明是默认值</b>的阈值让预警与预测可运行、可配置；
 * 它们随时可被租户覆盖，且能力声明里写清了这一点。<b>不冒充</b>平台下发的口径。
 */
public final class PerformanceMetricRules {

  private PerformanceMetricRules() {}

  // ================================================================ 指标词表

  /** 订单缺陷率（上游 16.1.1.1 拥有）。 */
  public static final String METRIC_ODR = "ODR";

  /** 迟发率（上游 16.1.1.1 拥有）。 */
  public static final String METRIC_LATE_SHIPMENT = "LATE_SHIPMENT_RATE";

  /** 取消率（上游 16.1.1.1 拥有）。 */
  public static final String METRIC_CANCELLATION = "CANCELLATION_RATE";

  /** 有效追踪率（上游 16.1.1.1 拥有）。 */
  public static final String METRIC_VALID_TRACKING = "VALID_TRACKING_RATE";

  /** 响应时间（**本模块自有**，单位小时）。 */
  public static final String METRIC_RESPONSE_TIME = "RESPONSE_TIME";

  /** 账户评级（**本模块自有**，单位星级）。 */
  public static final String METRIC_ACCOUNT_RATING = "ACCOUNT_RATING";

  /** 六项指标的固定顺序（界面按这个顺序渲染）。 */
  public static final List<String> METRIC_CODES =
      List.of(
          METRIC_ODR,
          METRIC_LATE_SHIPMENT,
          METRIC_CANCELLATION,
          METRIC_VALID_TRACKING,
          METRIC_RESPONSE_TIME,
          METRIC_ACCOUNT_RATING);

  /** 🔴 上游 16.1.1.1 拥有的四个码（时间序列在 {@code account_health_metric}）。 */
  public static final List<String> UPSTREAM_METRIC_CODES =
      List.of(METRIC_ODR, METRIC_LATE_SHIPMENT, METRIC_CANCELLATION, METRIC_VALID_TRACKING);

  /** 🔴 本模块自有的两个码（时间序列在 {@code account_health_performance_metric}）。 */
  public static final List<String> OWN_METRIC_CODES = List.of(METRIC_RESPONSE_TIME, METRIC_ACCOUNT_RATING);

  /** 指标的人话名。 */
  public static final Map<String, String> METRIC_LABELS =
      Map.of(
          METRIC_ODR, "订单缺陷率（ODR）",
          METRIC_LATE_SHIPMENT, "迟发率",
          METRIC_CANCELLATION, "取消率",
          METRIC_VALID_TRACKING, "有效追踪率",
          METRIC_RESPONSE_TIME, "响应时间",
          METRIC_ACCOUNT_RATING, "账户评级");

  // ================================================================ 量纲

  /** 比值量纲（0..1）。 */
  public static final String UNIT_RATIO = "RATIO";

  /** 小时量纲（0..8760）。 */
  public static final String UNIT_HOURS = "HOURS";

  /** 星级量纲（0..5）。 */
  public static final String UNIT_SCORE = "SCORE";

  /** 指标的人话量纲。 */
  public static final Map<String, String> UNIT_LABELS =
      Map.of(UNIT_RATIO, "比值（0..1）", UNIT_HOURS, "小时", UNIT_SCORE, "星级（0..5）");

  /** 每个指标用什么量纲。 */
  public static final Map<String, String> METRIC_UNITS =
      Map.of(
          METRIC_ODR, UNIT_RATIO,
          METRIC_LATE_SHIPMENT, UNIT_RATIO,
          METRIC_CANCELLATION, UNIT_RATIO,
          METRIC_VALID_TRACKING, UNIT_RATIO,
          METRIC_RESPONSE_TIME, UNIT_HOURS,
          METRIC_ACCOUNT_RATING, UNIT_SCORE);

  /** 各量纲的取值上限（与 V304 的 CHECK 一致）。 */
  public static final Map<String, BigDecimal> UNIT_MAX =
      Map.of(UNIT_RATIO, new BigDecimal("1"), UNIT_HOURS, new BigDecimal("8760"), UNIT_SCORE, new BigDecimal("5"));

  // ================================================================ 方向 / 来源 / 等级

  /** 越大越危险。 */
  public static final String DIRECTION_ABOVE_IS_BAD = "ABOVE_IS_BAD";

  /** 越小越危险。 */
  public static final String DIRECTION_BELOW_IS_BAD = "BELOW_IS_BAD";

  /** 平台同步来的。 */
  public static final String SOURCE_PLATFORM_SYNC = "PLATFORM_SYNC";

  /** 人工录入的。 */
  public static final String SOURCE_MANUAL = "MANUAL";

  /** 阈值来源：模块内置默认。 */
  public static final String RULE_SOURCE_BUILTIN = "BUILTIN_DEFAULT";

  /** 阈值来源：本模块的租户自定义预警阈值。 */
  public static final String RULE_SOURCE_CUSTOM = "TENANT_CUSTOM";

  /** 阈值来源：16.1.1.1 的租户自定义阈值。 */
  public static final String RULE_SOURCE_UPSTREAM = "UPSTREAM_TENANT_CUSTOM";

  /** 预警等级：严重。 */
  public static final String SEVERITY_CRITICAL = "CRITICAL";

  /** 预警等级：预警。 */
  public static final String SEVERITY_WARNING = "WARNING";

  /** 预警等级：量不到（**不是通过**）。 */
  public static final String SEVERITY_UNKNOWN = "UNKNOWN";

  /** 预警等级词表（筛选时只接受这三个值）。 */
  public static final List<String> SEVERITIES =
      List.of(SEVERITY_CRITICAL, SEVERITY_WARNING, SEVERITY_UNKNOWN);

  /** 阈值档位：命中严重阈值。 */
  public static final String THRESHOLD_CRITICAL = "CRITICAL";

  /** 阈值档位：命中警告阈值。 */
  public static final String THRESHOLD_WARN = "WARN";

  /** 阈值档位：量不到。 */
  public static final String THRESHOLD_UNKNOWN = "UNKNOWN";

  // ================================================================ 预测

  /** 预测模型：最小二乘线性回归。 */
  public static final String METHOD_LINEAR = "LINEAR_LEAST_SQUARES";

  /** 🔴 预测所需的最少已知点数（少于这个数就**无法预测**，绝不给假预测）。 */
  public static final int MIN_FORECAST_POINTS = 3;

  /** 预测结论：会跌破阈值。 */
  public static final String RESULT_WILL_BREACH = "WILL_BREACH";

  /** 预测结论：按当前趋势不会跌破。 */
  public static final String RESULT_NO_BREACH = "NO_BREACH";

  /** 预测结论：已经越线（那是现状，不是预测）。 */
  public static final String RESULT_ALREADY_BREACHED = "ALREADY_BREACHED";

  /** 预测结论：无法预测。 */
  public static final String RESULT_CANNOT_PREDICT = "CANNOT_PREDICT";

  // ================================================================ 阈值

  /**
   * 一条生效中的预警阈值。
   *
   * @param metricCode 指标编码
   * @param direction ABOVE_IS_BAD / BELOW_IS_BAD
   * @param unit RATIO / HOURS / SCORE
   * @param warnValue 警告阈值
   * @param criticalValue 严重阈值
   * @param ruleVersion 规则版本
   * @param ruleSource BUILTIN_DEFAULT / TENANT_CUSTOM / UPSTREAM_TENANT_CUSTOM
   */
  public record AlertThreshold(
      String metricCode,
      String direction,
      String unit,
      BigDecimal warnValue,
      BigDecimal criticalValue,
      long ruleVersion,
      String ruleSource) {

    /** 换一个来源标签（值不变）。 */
    public AlertThreshold withSource(String source) {
      return new AlertThreshold(
          metricCode, direction, unit, warnValue, criticalValue, ruleVersion, source);
    }
  }

  /**
   * 🔴 <b>模块内置默认预警阈值</b>。
   *
   * <p>这些数字是<b>本模块的默认政策</b>，**不是任何平台下发的口径**。
   * 四个比值指标的默认值与 16.1.1.1 的内置默认阈值一致（同一批默认政策），
   * 但**独立成表**（{@code account_health_performance_alert_rule}）——
   * 本模块的预警阈值与 16.1.1.1 的风险等级阈值是**两件事**，不共用一张表。
   */
  public static final Map<String, AlertThreshold> BUILTIN_ALERT_THRESHOLDS = builtinThresholds();

  private static Map<String, AlertThreshold> builtinThresholds() {
    Map<String, AlertThreshold> thresholds = new LinkedHashMap<>();
    thresholds.put(
        METRIC_ODR,
        new AlertThreshold(
            METRIC_ODR, DIRECTION_ABOVE_IS_BAD, UNIT_RATIO, decimal("0.0100"), decimal("0.0280"), 1L,
            RULE_SOURCE_BUILTIN));
    thresholds.put(
        METRIC_LATE_SHIPMENT,
        new AlertThreshold(
            METRIC_LATE_SHIPMENT, DIRECTION_ABOVE_IS_BAD, UNIT_RATIO, decimal("0.0400"), decimal("0.1000"), 1L,
            RULE_SOURCE_BUILTIN));
    thresholds.put(
        METRIC_CANCELLATION,
        new AlertThreshold(
            METRIC_CANCELLATION, DIRECTION_ABOVE_IS_BAD, UNIT_RATIO, decimal("0.0250"), decimal("0.0500"), 1L,
            RULE_SOURCE_BUILTIN));
    thresholds.put(
        METRIC_VALID_TRACKING,
        new AlertThreshold(
            METRIC_VALID_TRACKING, DIRECTION_BELOW_IS_BAD, UNIT_RATIO, decimal("0.9800"), decimal("0.9500"), 1L,
            RULE_SOURCE_BUILTIN));
    thresholds.put(
        METRIC_RESPONSE_TIME,
        new AlertThreshold(
            METRIC_RESPONSE_TIME, DIRECTION_ABOVE_IS_BAD, UNIT_HOURS, decimal("24.000000"), decimal("48.000000"), 1L,
            RULE_SOURCE_BUILTIN));
    thresholds.put(
        METRIC_ACCOUNT_RATING,
        new AlertThreshold(
            METRIC_ACCOUNT_RATING, DIRECTION_BELOW_IS_BAD, UNIT_SCORE, decimal("4.000000"), decimal("3.000000"), 1L,
            RULE_SOURCE_BUILTIN));
    return Map.copyOf(thresholds);
  }

  private static BigDecimal decimal(String raw) {
    return new BigDecimal(raw).setScale(6, RoundingMode.HALF_UP);
  }

  /**
   * 生效阈值 = 本模块自定义优先，其次上游租户覆盖，最后内置默认。
   *
   * <p>🔴 解析顺序**写死在这里**（前端不重算），每一档都带得出
   * <b>用的是哪条阈值</b>（{@code ruleSource}）。
   */
  public static Map<String, AlertThreshold> effectiveThresholds(
      Map<String, AlertThreshold> moduleOverrides, Map<String, AlertThreshold> upstreamOverrides) {
    Map<String, AlertThreshold> merged = new LinkedHashMap<>(BUILTIN_ALERT_THRESHOLDS);
    if (upstreamOverrides != null) {
      for (Map.Entry<String, AlertThreshold> entry : upstreamOverrides.entrySet()) {
        AlertThreshold threshold = entry.getValue();
        if (threshold != null) {
          merged.put(entry.getKey(), threshold.withSource(RULE_SOURCE_UPSTREAM));
        }
      }
    }
    if (moduleOverrides != null) {
      for (Map.Entry<String, AlertThreshold> entry : moduleOverrides.entrySet()) {
        AlertThreshold threshold = entry.getValue();
        if (threshold != null) {
          merged.put(entry.getKey(), threshold.withSource(RULE_SOURCE_CUSTOM));
        }
      }
    }
    return merged;
  }

  /** 生效阈值集合的来源（全部自定义 / 全部上游 / 全部默认 / 混合）。 */
  public static String ruleSourceOf(Map<String, AlertThreshold> thresholds) {
    boolean custom = false;
    boolean upstream = false;
    boolean builtin = false;
    for (String code : METRIC_CODES) {
      AlertThreshold threshold = thresholds == null ? null : thresholds.get(code);
      if (threshold == null) {
        continue;
      }
      switch (threshold.ruleSource()) {
        case RULE_SOURCE_CUSTOM -> custom = true;
        case RULE_SOURCE_UPSTREAM -> upstream = true;
        default -> builtin = true;
      }
    }
    int kinds = (custom ? 1 : 0) + (upstream ? 1 : 0) + (builtin ? 1 : 0);
    if (kinds > 1) {
      return "MIXED";
    }
    if (custom) {
      return RULE_SOURCE_CUSTOM;
    }
    if (upstream) {
      return RULE_SOURCE_UPSTREAM;
    }
    return RULE_SOURCE_BUILTIN;
  }

  /** 生效阈值集合的版本（取用到的最高版本）。 */
  public static long ruleVersionOf(Map<String, AlertThreshold> thresholds) {
    long version = 1L;
    if (thresholds == null) {
      return version;
    }
    for (String code : METRIC_CODES) {
      AlertThreshold threshold = thresholds.get(code);
      if (threshold != null) {
        version = Math.max(version, threshold.ruleVersion());
      }
    }
    return version;
  }

  /** 阈值来源的人话。 */
  public static String ruleSourceLabel(String source) {
    switch (source == null ? "" : source) {
      case RULE_SOURCE_BUILTIN:
        return "模块内置默认阈值";
      case RULE_SOURCE_CUSTOM:
        return "本模块租户自定义阈值";
      case RULE_SOURCE_UPSTREAM:
        return "16.1.1.1 租户阈值";
      case "MIXED":
        return "部分自定义 / 部分默认";
      default:
        return source == null ? "未声明" : source;
    }
  }

  /** 方向的人话。 */
  public static String directionText(String direction) {
    if (DIRECTION_BELOW_IS_BAD.equals(direction)) {
      return "低于阈值即危险";
    }
    if (DIRECTION_ABOVE_IS_BAD.equals(direction)) {
      return "高于阈值即危险";
    }
    return direction;
  }

  /** 来源的人话。 */
  public static String sourceLabel(String source) {
    if (SOURCE_MANUAL.equals(source)) {
      return "人工录入";
    }
    if (SOURCE_PLATFORM_SYNC.equals(source)) {
      return "平台同步";
    }
    return source;
  }

  /** 预警等级的人话。 */
  public static String severityLabel(String severity) {
    switch (severity == null ? "" : severity) {
      case SEVERITY_CRITICAL:
        return "严重（命中严重阈值）";
      case SEVERITY_WARNING:
        return "预警（命中警告阈值）";
      case SEVERITY_UNKNOWN:
        return "量不到（不算通过）";
      default:
        return severity;
    }
  }

  /**
   * 🔴 越线档位的人话。
   *
   * <p>注意：<b>越线档位用的是阈值词表</b>（{@code WARN} / {@code CRITICAL}），
   * 与预警等级词表（{@code WARNING} / {@code CRITICAL} / {@code UNKNOWN}）**不是同一套** ——
   * 越线说的是「越的是哪一档阈值」，预警等级说的是「这条预警有多严重」。
   */
  public static String breachLevelLabel(String breachLevel) {
    switch (breachLevel == null ? "" : breachLevel) {
      case THRESHOLD_CRITICAL:
        return "严重档（越过严重阈值）";
      case THRESHOLD_WARN:
        return "警告档（越过警告阈值）";
      default:
        return breachLevel == null ? "" : breachLevel;
    }
  }

  // ================================================================ 数值展示

  /** 比值 → 百分数字符串（0..1 → 0.00%）。 */
  public static String percent(BigDecimal ratio) {
    if (ratio == null) {
      return "未知";
    }
    return ratio
        .multiply(new BigDecimal("100"))
        .setScale(2, RoundingMode.HALF_UP)
        .toPlainString()
        + "%";
  }

  /** 按量纲格式化一个值（比值 → %，小时 → 小时，星级 → 分）。 */
  public static String formatValue(BigDecimal value, String unit) {
    if (value == null) {
      return "未知";
    }
    if (UNIT_HOURS.equals(unit)) {
      return value.setScale(2, RoundingMode.HALF_UP).toPlainString() + " 小时";
    }
    if (UNIT_SCORE.equals(unit)) {
      return value.setScale(2, RoundingMode.HALF_UP).toPlainString() + " 分";
    }
    return percent(value);
  }

  // ================================================================ 趋势点

  /**
   * 趋势序列里的一个点。
   *
   * @param periodStart 周期起点
   * @param periodEnd 周期终点
   * @param value **0..1 比值 / 小时 / 星级**（随 unit）；**null = 该周期量不到**
   * @param source PLATFORM_SYNC / MANUAL
   * @param capturedAt 采集 / 录入时刻
   * @param note 备注
   */
  public record TrendPoint(
      String periodStart, String periodEnd, BigDecimal value, String source, String capturedAt, String note) {

    /** 是否量到了。 */
    public boolean known() {
      return value != null;
    }
  }

  /** 预测里用到的那个周期（写进凭据，可复核）。 */
  public record UsedPeriod(int index, String periodStart, String periodEnd, BigDecimal value) {}

  /**
   * 🔴 均值 —— <b>分母只算量到的点</b>。
   *
   * @return 均值；一个都没量到时返回 null（**不是 0**）
   */
  public static BigDecimal averageOf(List<TrendPoint> points) {
    if (points == null) {
      return null;
    }
    BigDecimal sum = BigDecimal.ZERO;
    int known = 0;
    for (TrendPoint point : points) {
      if (point == null || point.value() == null) {
        continue;
      }
      sum = sum.add(point.value());
      known++;
    }
    if (known == 0) {
      return null;
    }
    return sum.divide(BigDecimal.valueOf(known), 6, RoundingMode.HALF_UP);
  }

  /** 均值的分母说明（**量不到的不进分母**，但要写清楚有几个量不到）。 */
  public static String averageNote(List<TrendPoint> points) {
    int known = 0;
    int unknown = 0;
    if (points != null) {
      for (TrendPoint point : points) {
        if (point != null && point.value() != null) {
          known++;
        } else {
          unknown++;
        }
      }
    }
    if (known == 0) {
      return "一个周期都没量到 ⇒ **算不出均值**（分母为 0，不是 0）。";
    }
    return "均值分母 = **量到的 " + known + " 个周期**（窗口共 " + (known + unknown)
        + " 个周期，其中 " + unknown + " 个量不到**不进分母**）。";
  }

  /**
   * 🔴 环比 —— 与**序列里上一个周期**比。
   *
   * @return 变化量；上一个周期量不到 / 没有上一个周期时返回 null
   */
  public static BigDecimal changeOf(List<TrendPoint> points) {
    if (points == null || points.size() < 2) {
      return null;
    }
    TrendPoint last = points.get(points.size() - 1);
    TrendPoint previous = points.get(points.size() - 2);
    if (last == null || previous == null || last.value() == null || previous.value() == null) {
      return null;
    }
    return last.value().subtract(previous.value()).setScale(6, RoundingMode.HALF_UP);
  }

  /** 环比的口径说明（**上一个周期量不到就不可知**，不许当成 0）。 */
  public static String changeNote(List<TrendPoint> points) {
    if (points == null || points.size() < 2) {
      return "序列里还没有第二个周期 ⇒ **环比不可知**。";
    }
    TrendPoint last = points.get(points.size() - 1);
    TrendPoint previous = points.get(points.size() - 2);
    if (last == null || last.value() == null) {
      return "最后一个周期**量不到** ⇒ **环比不可知**（不是「没变化」）。";
    }
    if (previous == null || previous.value() == null) {
      return "对比的是序列里**上一个周期**，而上一个周期**量不到** ⇒ **环比不可知**"
          + "（量不到不是 0，不能当成没变化）。";
    }
    return "环比对比的是序列里**上一个周期**（"
        + previous.periodStart()
        + " ~ "
        + previous.periodEnd()
        + "）的区间值 —— 这是**区间口径**，不是瞬时点。";
  }

  // ================================================================ 预警判定

  /**
   * 一次预警判定。
   *
   * @param severity CRITICAL / WARNING / UNKNOWN / null（已知且未命中 ⇒ 没有预警）
   * @param thresholdKind CRITICAL / WARN / UNKNOWN
   * @param thresholdValue 命中的阈值（量不到时为 null）
   * @param message 人话说明
   */
  public record MetricVerdict(
      String severity, String thresholdKind, BigDecimal thresholdValue, String message) {}

  /**
   * 🔴 预警判定（服务端算，前端只显示结论与依据）。
   *
   * <ul>
   *   <li>量不到（无值）⇒ {@code UNKNOWN}，<b>不算通过</b>；</li>
   *   <li>命中严重阈值 ⇒ {@code CRITICAL}；命中警告阈值 ⇒ {@code WARNING}；</li>
   *   <li>已知且都未命中 ⇒ 返回 {@code null} severity（<b>没有预警就没有凭据</b>）。</li>
   * </ul>
   */
  public static MetricVerdict evaluate(String metricCode, BigDecimal value, AlertThreshold threshold) {
    String label = METRIC_LABELS.getOrDefault(metricCode, metricCode);
    if (value == null) {
      return new MetricVerdict(
          SEVERITY_UNKNOWN,
          THRESHOLD_UNKNOWN,
          null,
          label + "量不到（这个周期没有采集 / 录入这一项），**不算通过**；这一项不进已知数。");
    }
    if (threshold == null) {
      return new MetricVerdict(
          SEVERITY_UNKNOWN,
          THRESHOLD_UNKNOWN,
          null,
          label + "没有可用的阈值规则，判不了（**不是「合格」**）。");
    }
    String unit = threshold.unit() == null ? UNIT_RATIO : threshold.unit();
    if (exceeds(value, threshold.criticalValue(), threshold.direction())) {
      return new MetricVerdict(
          SEVERITY_CRITICAL,
          THRESHOLD_CRITICAL,
          threshold.criticalValue(),
          label + " " + formatValue(value, unit) + " 已超过严重阈值 "
              + formatValue(threshold.criticalValue(), unit) + "（" + directionText(threshold.direction()) + "）。");
    }
    if (exceeds(value, threshold.warnValue(), threshold.direction())) {
      return new MetricVerdict(
          SEVERITY_WARNING,
          THRESHOLD_WARN,
          threshold.warnValue(),
          label + " " + formatValue(value, unit) + " 已超过警告阈值 "
              + formatValue(threshold.warnValue(), unit) + "（" + directionText(threshold.direction()) + "）。");
    }
    return new MetricVerdict(
        null,
        null,
        null,
        label + " " + formatValue(value, unit) + "，未超过警告阈值 "
            + formatValue(threshold.warnValue(), unit) + "。");
  }

  /** 越大越危险 ⇒ value ≥ threshold；越小越危险 ⇒ value ≤ threshold。 */
  static boolean exceeds(BigDecimal value, BigDecimal threshold, String direction) {
    if (value == null || threshold == null) {
      return false;
    }
    if (DIRECTION_BELOW_IS_BAD.equals(direction)) {
      return value.compareTo(threshold) <= 0;
    }
    return value.compareTo(threshold) >= 0;
  }

  // ================================================================ 跌破预测

  /**
   * 一次跌破预测的结论。
   *
   * @param result WILL_BREACH / NO_BREACH / ALREADY_BREACHED / CANNOT_PREDICT
   * @param reason 人话说明（**CANNOT_PREDICT 时必须说清为什么**）
   * @param breachLevel WARN / CRITICAL（null = 没有预测到越线）
   * @param breachInPeriods 从**最后一个量到的周期**起，第几个周期会越线
   * @param predictedValue 那个周期的预测值
   * @param thresholdValue 越的是哪条阈值
   * @param thresholdKind WARN / CRITICAL
   * @param slope 线性回归斜率（每周期）
   * @param intercept 线性回归截距
   * @param knownPoints 量到的点数
   * @param unknownPoints 量不到的点数
   * @param usedPeriods 用到的周期明细
   */
  public record Forecast(
      String result,
      String reason,
      String breachLevel,
      Integer breachInPeriods,
      BigDecimal predictedValue,
      BigDecimal thresholdValue,
      String thresholdKind,
      BigDecimal slope,
      BigDecimal intercept,
      int knownPoints,
      int unknownPoints,
      List<UsedPeriod> usedPeriods) {}

  /**
   * 🔴 跌破预测。
   *
   * <p><b>口径（明确、可解释、可复核）</b>：
   *
   * <ol>
   *   <li>取窗口内**最近 N 个周期**（窗口内每个周期占一个序号，量不到的**占位但不进回归**）；</li>
   *   <li>对**量到的**点做<b>最小二乘线性回归</b>（x = 周期序号，y = 值）；</li>
   *   <li>以**最后一个量到的周期**为起点，向后推 k = 1..horizon 个周期，
   *       看第几个周期沿危险方向越线（ABOVE_IS_BAD ⇒ 越高越危险；BELOW_IS_BAD ⇒ 越低越危险）；</li>
   *   <li>已知点数不足 {@value #MIN_FORECAST_POINTS} 个 ⇒ <b>无法预测</b>，
   *       如实给出原因，<b>绝不给一个假预测</b>。</li>
   * </ol>
   */
  public static Forecast forecast(List<TrendPoint> window, AlertThreshold threshold, int horizon) {
    List<TrendPoint> points = window == null ? List.of() : window;
    List<UsedPeriod> used = new ArrayList<>();
    int known = 0;
    int unknown = 0;
    BigDecimal sumX = BigDecimal.ZERO;
    BigDecimal sumY = BigDecimal.ZERO;
    BigDecimal sumXY = BigDecimal.ZERO;
    BigDecimal sumXX = BigDecimal.ZERO;
    int lastKnownIndex = -1;
    BigDecimal lastKnownValue = null;

    for (int index = 0; index < points.size(); index++) {
      TrendPoint point = points.get(index);
      if (point == null || point.value() == null) {
        unknown++;
        continue;
      }
      known++;
      used.add(new UsedPeriod(index, point.periodStart(), point.periodEnd(), point.value()));
      BigDecimal x = BigDecimal.valueOf(index);
      BigDecimal y = point.value();
      sumX = sumX.add(x);
      sumY = sumY.add(y);
      sumXY = sumXY.add(x.multiply(y));
      sumXX = sumXX.add(x.multiply(x));
      lastKnownIndex = index;
      lastKnownValue = y;
    }

    String label = threshold == null ? "该指标" : METRIC_LABELS.getOrDefault(threshold.metricCode(), threshold.metricCode());
    String unit = threshold == null ? UNIT_RATIO : (threshold.unit() == null ? UNIT_RATIO : threshold.unit());

    if (known == 0) {
      return new Forecast(
          RESULT_CANNOT_PREDICT,
          label + "：窗口内**一个量到的周期都没有**（"
              + unknown + " 个周期全是量不到）⇒ **无法预测**。"
              + "先补齐这个指标的周期数据再来预测 —— 绝不给一个假预测。",
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          known,
          unknown,
          List.of());
    }
    if (known < MIN_FORECAST_POINTS) {
      return new Forecast(
          RESULT_CANNOT_PREDICT,
          label + "：已知点数不足（线性回归至少需要 " + MIN_FORECAST_POINTS + " 个量到的周期，当前只有 "
              + known + " 个，另有 " + unknown + " 个量不到）⇒ **无法预测**。",
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          known,
          unknown,
          List.copyOf(used));
    }
    if (threshold == null) {
      return new Forecast(
          RESULT_CANNOT_PREDICT,
          label + "：没有可用的阈值规则 ⇒ **无法预测会不会跌破阈值**。",
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          known,
          unknown,
          List.copyOf(used));
    }

    MathContext mc = MathContext.DECIMAL128;
    BigDecimal n = BigDecimal.valueOf(known);
    BigDecimal denominator = n.multiply(sumXX).subtract(sumX.multiply(sumX));
    if (denominator.signum() == 0) {
      return new Forecast(
          RESULT_CANNOT_PREDICT,
          label + "：窗口内这些周期的序号无法区分（分母为 0）⇒ **无法预测**。",
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          known,
          unknown,
          List.copyOf(used));
    }
    BigDecimal slope =
        n.multiply(sumXY).subtract(sumX.multiply(sumY)).divide(denominator, mc);
    BigDecimal intercept = sumY.subtract(slope.multiply(sumX)).divide(n, mc);
    BigDecimal slope6 = slope.setScale(6, RoundingMode.HALF_UP);
    BigDecimal intercept6 = intercept.setScale(6, RoundingMode.HALF_UP);

    // 已经越线 ⇒ 那是现状，不是预测。
    if (exceeds(lastKnownValue, threshold.criticalValue(), threshold.direction())) {
      return new Forecast(
          RESULT_ALREADY_BREACHED,
          label + "：**当前周期已经命中严重阈值** "
              + formatValue(threshold.criticalValue(), unit) + "（这是**现状**，不是预测）。",
          THRESHOLD_CRITICAL,
          null,
          null,
          threshold.criticalValue(),
          THRESHOLD_CRITICAL,
          slope6,
          intercept6,
          known,
          unknown,
          List.copyOf(used));
    }
    if (exceeds(lastKnownValue, threshold.warnValue(), threshold.direction())) {
      return new Forecast(
          RESULT_ALREADY_BREACHED,
          label + "：**当前周期已经命中警告阈值** "
              + formatValue(threshold.warnValue(), unit) + "（这是**现状**，不是预测）。",
          THRESHOLD_WARN,
          null,
          null,
          threshold.warnValue(),
          THRESHOLD_WARN,
          slope6,
          intercept6,
          known,
          unknown,
          List.copyOf(used));
    }

    int firstWarn = -1;
    int firstCritical = -1;
    BigDecimal predictedWarn = null;
    BigDecimal predictedCritical = null;
    for (int step = 1; step <= horizon; step++) {
      BigDecimal x = BigDecimal.valueOf(lastKnownIndex + step);
      BigDecimal predicted = intercept.add(slope.multiply(x)).setScale(6, RoundingMode.HALF_UP);
      if (firstWarn < 0 && exceeds(predicted, threshold.warnValue(), threshold.direction())) {
        firstWarn = step;
        predictedWarn = predicted;
      }
      if (firstCritical < 0 && exceeds(predicted, threshold.criticalValue(), threshold.direction())) {
        firstCritical = step;
        predictedCritical = predicted;
      }
      if (firstWarn > 0 && firstCritical > 0) {
        break;
      }
    }

    if (firstCritical > 0 && (firstWarn < 0 || firstCritical <= firstWarn)) {
      return new Forecast(
          RESULT_WILL_BREACH,
          label + "：按最近 " + known + " 个已知周期做最小二乘线性外推（斜率 "
              + slope6.toPlainString() + " / 周期），**第 " + firstCritical
              + " 个周期**预计达到 " + formatValue(predictedCritical, unit)
              + "，越过严重阈值 " + formatValue(threshold.criticalValue(), unit) + "。"
              + "提前知道就能提前改。",
          THRESHOLD_CRITICAL,
          firstCritical,
          predictedCritical,
          threshold.criticalValue(),
          THRESHOLD_CRITICAL,
          slope6,
          intercept6,
          known,
          unknown,
          List.copyOf(used));
    }
    if (firstWarn > 0) {
      return new Forecast(
          RESULT_WILL_BREACH,
          label + "：按最近 " + known + " 个已知周期做最小二乘线性外推（斜率 "
              + slope6.toPlainString() + " / 周期），**第 " + firstWarn
              + " 个周期**预计达到 " + formatValue(predictedWarn, unit)
              + "，越过警告阈值 " + formatValue(threshold.warnValue(), unit) + "。"
              + "提前知道就能提前改。",
          THRESHOLD_WARN,
          firstWarn,
          predictedWarn,
          threshold.warnValue(),
          THRESHOLD_WARN,
          slope6,
          intercept6,
          known,
          unknown,
          List.copyOf(used));
    }

    return new Forecast(
        RESULT_NO_BREACH,
        label + "：按最近 " + known + " 个已知周期做最小二乘线性外推（斜率 "
            + slope6.toPlainString() + " / 周期，趋势" + trendText(threshold.direction(), slope)
            + "），未来 " + horizon + " 个周期内**不会**越过警告阈值 "
            + formatValue(threshold.warnValue(), unit) + "。",
        null,
        null,
        null,
        null,
        null,
        slope6,
        intercept6,
        known,
        unknown,
        List.copyOf(used));
  }

  /** 趋势方向的人话（相对阈值而言）。 */
  private static String trendText(String direction, BigDecimal slope) {
    if (slope.signum() == 0) {
      return "持平";
    }
    boolean worsening =
        DIRECTION_BELOW_IS_BAD.equals(direction) ? slope.signum() < 0 : slope.signum() > 0;
    return worsening ? "在靠近阈值" : "在远离阈值";
  }

  /** 预测模型的人话说明（写进凭据，**可解释**）。 */
  public static String methodNote(int windowPoints, int knownPoints, int horizon) {
    return "口径：取窗口内最近 " + windowPoints + " 个周期（每个周期占一个序号），"
        + "对其中**量到的** " + knownPoints + " 个点做**最小二乘线性回归**（x = 周期序号，y = 值），"
        + "以**最后一个量到的周期**为起点向后推最多 " + horizon + " 个周期，"
        + "看第几个周期沿危险方向越线。**量不到的周期占位但不进回归**（不是 0）。";
  }

  /** 预测结论的人话。 */
  public static String resultLabel(String result) {
    switch (result == null ? "" : result) {
      case RESULT_WILL_BREACH:
        return "会跌破阈值";
      case RESULT_NO_BREACH:
        return "按当前趋势不会跌破";
      case RESULT_ALREADY_BREACHED:
        return "已经越线（现状）";
      case RESULT_CANNOT_PREDICT:
        return "无法预测";
      default:
        return result;
    }
  }
}
