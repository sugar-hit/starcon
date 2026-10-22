package com.starcon.channel;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 16.1.1.1 账号健康总览的<b>规则层</b>：指标口径、阈值、风险等级判定、跨店铺排序。
 *
 * <p>本类不碰数据库、不碰 HTTP，**可单测**（与 {@link CompliancePrecheckRules}、
 * {@link BrandAuthorizationRules} 同一手法）。
 *
 * <p>🔴🔴 本类守的是四件事：
 *
 * <ol>
 *   <li><b>量不到 ≠ 0</b>：{@code metricValue=null} 是「这一项我们没测到」，
 *       <b>不进已知数</b>，也**不算通过**。</li>
 *   <li><b>量不到 ≠ 低风险</b>：四项没量全，就**不能**断言 {@code HEALTHY} ——
 *       那样等于把「没查」写成「健康」。</li>
 *   <li><b>阈值在服务端</b>：默认阈值是本模块的<b>默认政策</b>（不是平台事实），
 *       可按租户覆盖；覆盖产生新版本，历史判定仍然是旧判定。</li>
 *   <li><b>排序真的按风险排</b>，而且每一家都带得出「为什么排在这个位置」。</li>
 * </ol>
 *
 * <p>🔴 <b>为什么默认阈值不是「平台口径」</b>：各平台的绩效口径并不通用
 * （Amazon 的 ODR 与其它平台的缺陷率定义不同、阈值也不同）。本模块在没有平台绩效
 * 适配器的前提下，用一组<b>写明是默认值</b>的阈值让判定逻辑可运行、可配置；
 * 它们随时可被租户覆盖，且能力声明里写清了这一点。**不冒充**平台下发的口径。
 */
public final class AccountHealthRules {

  private AccountHealthRules() {}

  // ================================================================ 词表

  /** 订单缺陷率。 */
  public static final String METRIC_ODR = "ODR";

  /** 迟发率。 */
  public static final String METRIC_LATE_SHIPMENT = "LATE_SHIPMENT_RATE";

  /** 取消率。 */
  public static final String METRIC_CANCELLATION = "CANCELLATION_RATE";

  /** 有效追踪率。 */
  public static final String METRIC_VALID_TRACKING = "VALID_TRACKING_RATE";

  /** 四项指标的固定顺序（界面按这个顺序渲染）。 */
  public static final List<String> METRIC_CODES =
      List.of(METRIC_ODR, METRIC_LATE_SHIPMENT, METRIC_CANCELLATION, METRIC_VALID_TRACKING);

  /** 指标的人话名。 */
  public static final Map<String, String> METRIC_LABELS =
      Map.of(
          METRIC_ODR, "订单缺陷率（ODR）",
          METRIC_LATE_SHIPMENT, "迟发率",
          METRIC_CANCELLATION, "取消率",
          METRIC_VALID_TRACKING, "有效追踪率");

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

  /** 阈值来源：租户自定义。 */
  public static final String RULE_SOURCE_CUSTOM = "TENANT_CUSTOM";

  /** 阈值来源：一部分自定义、一部分默认。 */
  public static final String RULE_SOURCE_MIXED = "MIXED";

  /** 风险等级：严重（先处理）。 */
  public static final String LEVEL_CRITICAL = "CRITICAL";

  /** 风险等级：预警。 */
  public static final String LEVEL_WARNING = "WARNING";

  /** 风险等级：健康。 */
  public static final String LEVEL_HEALTHY = "HEALTHY";

  /** 风险等级：判不了（量不全）。 */
  public static final String LEVEL_UNKNOWN = "UNKNOWN";

  /** 依据档位里的「未命中」（不是风险等级）。 */
  public static final String LEVEL_NONE = "NONE";

  /** 风险等级词表（筛选时只接受这四个值）。 */
  public static final List<String> RISK_LEVELS =
      List.of(LEVEL_CRITICAL, LEVEL_WARNING, LEVEL_HEALTHY, LEVEL_UNKNOWN);

  /** 依据档位：命中严重阈值。 */
  public static final String THRESHOLD_CRITICAL = "CRITICAL";

  /** 依据档位：命中警告阈值。 */
  public static final String THRESHOLD_WARN = "WARN";

  /** 依据档位：未命中。 */
  public static final String THRESHOLD_NONE = "NONE";

  /** 依据档位：量不到。 */
  public static final String THRESHOLD_UNKNOWN = "UNKNOWN";

  // ================================================================ 阈值

  /**
   * 一条阈值规则。
   *
   * @param metricCode 指标编码
   * @param direction ABOVE_IS_BAD / BELOW_IS_BAD
   * @param warnValue 警告阈值（0..1 比值）
   * @param criticalValue 严重阈值（0..1 比值）
   * @param ruleVersion 规则版本
   * @param ruleSource BUILTIN_DEFAULT / TENANT_CUSTOM
   */
  public record Threshold(
      String metricCode,
      String direction,
      BigDecimal warnValue,
      BigDecimal criticalValue,
      long ruleVersion,
      String ruleSource) {}

  /**
   * 🔴 <b>模块内置默认阈值</b>。
   *
   * <p>这些数字是<b>本模块的默认政策</b>，**不是任何平台下发的口径** —— 各平台对
   * ODR / 迟发率 / 取消率 / 有效追踪率的定义与阈值都不一样。写成代码里的常量而不是
   * 写进前端，是为了让「阈值归服务端算」这件事成立；可被租户覆盖（覆盖后成为
   * {@code TENANT_CUSTOM} 并推进版本）。
   */
  public static final Map<String, Threshold> BUILTIN_THRESHOLDS = builtinThresholds();

  private static Map<String, Threshold> builtinThresholds() {
    Map<String, Threshold> thresholds = new LinkedHashMap<>();
    thresholds.put(
        METRIC_ODR,
        new Threshold(
            METRIC_ODR, DIRECTION_ABOVE_IS_BAD, decimal("0.0100"), decimal("0.0280"), 1L,
            RULE_SOURCE_BUILTIN));
    thresholds.put(
        METRIC_LATE_SHIPMENT,
        new Threshold(
            METRIC_LATE_SHIPMENT, DIRECTION_ABOVE_IS_BAD, decimal("0.0400"), decimal("0.1000"), 1L,
            RULE_SOURCE_BUILTIN));
    thresholds.put(
        METRIC_CANCELLATION,
        new Threshold(
            METRIC_CANCELLATION, DIRECTION_ABOVE_IS_BAD, decimal("0.0250"), decimal("0.0500"), 1L,
            RULE_SOURCE_BUILTIN));
    thresholds.put(
        METRIC_VALID_TRACKING,
        new Threshold(
            METRIC_VALID_TRACKING, DIRECTION_BELOW_IS_BAD, decimal("0.9800"), decimal("0.9500"), 1L,
            RULE_SOURCE_BUILTIN));
    return Map.copyOf(thresholds);
  }

  private static BigDecimal decimal(String raw) {
    return new BigDecimal(raw).setScale(4, RoundingMode.HALF_UP);
  }

  /**
   * 生效阈值 = 租户自定义优先，没覆盖的用内置默认。
   *
   * @param overrides 租户自定义的规则（可空）
   */
  public static Map<String, Threshold> effectiveThresholds(Map<String, Threshold> overrides) {
    Map<String, Threshold> merged = new LinkedHashMap<>(BUILTIN_THRESHOLDS);
    if (overrides != null) {
      for (Map.Entry<String, Threshold> entry : overrides.entrySet()) {
        if (entry.getValue() != null) {
          merged.put(entry.getKey(), entry.getValue());
        }
      }
    }
    return merged;
  }

  /** 生效阈值集合的来源（全部自定义 / 全部默认 / 混合）。 */
  public static String ruleSourceOf(Map<String, Threshold> thresholds) {
    boolean custom = false;
    boolean builtin = false;
    for (String code : METRIC_CODES) {
      Threshold threshold = thresholds.get(code);
      if (threshold == null) {
        continue;
      }
      if (RULE_SOURCE_CUSTOM.equals(threshold.ruleSource())) {
        custom = true;
      } else {
        builtin = true;
      }
    }
    if (custom && builtin) {
      return RULE_SOURCE_MIXED;
    }
    return custom ? RULE_SOURCE_CUSTOM : RULE_SOURCE_BUILTIN;
  }

  /** 生效阈值集合的版本（取用到的最高版本）。 */
  public static long ruleVersionOf(Map<String, Threshold> thresholds) {
    long version = 1L;
    for (String code : METRIC_CODES) {
      Threshold threshold = thresholds.get(code);
      if (threshold != null) {
        version = Math.max(version, threshold.ruleVersion());
      }
    }
    return version;
  }

  // ================================================================ 判定

  /**
   * 一项指标的当前读数。
   *
   * @param metricCode 指标编码
   * @param value 0..1 比值；**null = 量不到**
   * @param source PLATFORM_SYNC / MANUAL
   * @param periodStart 周期起点
   * @param periodEnd 周期终点
   * @param capturedAt 采集 / 录入时刻
   * @param note 备注
   */
  public record MetricReading(
      String metricCode,
      BigDecimal value,
      String source,
      String periodStart,
      String periodEnd,
      String capturedAt,
      String note) {}

  /**
   * 一次判定的结果。
   *
   * @param riskLevel CRITICAL / WARNING / HEALTHY / UNKNOWN
   * @param knownMetricCount 量到了几项（0..4）
   * @param breachCount 命中阈值的指标数
   * @param reasons 逐条依据
   * @param unknownMetricCodes 量不到的指标编码
   * @param worstMargin 最大「越线幅度」（同等级内的排序依据）
   */
  public record Evaluation(
      String riskLevel,
      int knownMetricCount,
      int breachCount,
      List<AccountHealthModels.AccountHealthReason> reasons,
      List<String> unknownMetricCodes,
      BigDecimal worstMargin) {}

  /**
   * 🔴 风险等级判定。
   *
   * <p>口径（服务端算，前端只显示）：
   *
   * <ul>
   *   <li>一项指标<b>量不到</b>（无值）⇒ 该项不算已知、<b>不算通过</b>，产出一条 UNKNOWN 依据；</li>
   *   <li>已知项命中严重阈值 ⇒ <b>CRITICAL</b>；命中警告阈值 ⇒ <b>WARNING</b>；</li>
   *   <li>已知项都没命中，但<b>四项没量全</b> ⇒ <b>UNKNOWN</b>（不能断言健康）；</li>
   *   <li>四项全量到且都未命中 ⇒ <b>HEALTHY</b>；一项都没量到 ⇒ <b>UNKNOWN</b>。</li>
   * </ul>
   */
  public static Evaluation evaluate(
      Map<String, MetricReading> readings, Map<String, Threshold> thresholds) {
    List<AccountHealthModels.AccountHealthReason> reasons = new ArrayList<>();
    List<String> unknown = new ArrayList<>();
    int known = 0;
    int breaches = 0;
    boolean critical = false;
    boolean warning = false;
    BigDecimal worstMargin = BigDecimal.ZERO;

    for (String code : METRIC_CODES) {
      MetricReading reading = readings == null ? null : readings.get(code);
      Threshold threshold = thresholds == null ? null : thresholds.get(code);
      String label = METRIC_LABELS.getOrDefault(code, code);

      if (reading == null || reading.value() == null) {
        unknown.add(code);
        reasons.add(
            new AccountHealthModels.AccountHealthReason(
                code,
                label,
                LEVEL_UNKNOWN,
                null,
                null,
                THRESHOLD_UNKNOWN,
                threshold == null ? null : threshold.direction(),
                label + "量不到（没有采集或录入这一项），**不算通过**；这一项不进已知数。"));
        continue;
      }
      if (threshold == null) {
        unknown.add(code);
        reasons.add(
            new AccountHealthModels.AccountHealthReason(
                code,
                label,
                LEVEL_UNKNOWN,
                reading.value(),
                null,
                THRESHOLD_UNKNOWN,
                null,
                label + "没有可用的阈值规则，判不了（不是「合格」）。"));
        continue;
      }

      known++;
      BigDecimal value = reading.value();
      boolean hitsCritical = exceeds(value, threshold.criticalValue(), threshold.direction());
      boolean hitsWarn = exceeds(value, threshold.warnValue(), threshold.direction());

      if (hitsCritical) {
        critical = true;
        breaches++;
        BigDecimal margin = margin(value, threshold.criticalValue(), threshold.direction());
        if (margin.compareTo(worstMargin) > 0) {
          worstMargin = margin;
        }
        reasons.add(
            new AccountHealthModels.AccountHealthReason(
                code,
                label,
                LEVEL_CRITICAL,
                value,
                threshold.criticalValue(),
                THRESHOLD_CRITICAL,
                threshold.direction(),
                label + " " + percent(value) + " 已超过严重阈值 " + percent(threshold.criticalValue())
                    + "（" + directionText(threshold.direction()) + "）。"));
      } else if (hitsWarn) {
        warning = true;
        breaches++;
        BigDecimal margin = margin(value, threshold.warnValue(), threshold.direction());
        if (margin.compareTo(worstMargin) > 0) {
          worstMargin = margin;
        }
        reasons.add(
            new AccountHealthModels.AccountHealthReason(
                code,
                label,
                LEVEL_WARNING,
                value,
                threshold.warnValue(),
                THRESHOLD_WARN,
                threshold.direction(),
                label + " " + percent(value) + " 已超过警告阈值 " + percent(threshold.warnValue())
                    + "（" + directionText(threshold.direction()) + "）。"));
      } else {
        reasons.add(
            new AccountHealthModels.AccountHealthReason(
                code,
                label,
                LEVEL_NONE,
                value,
                threshold.warnValue(),
                THRESHOLD_NONE,
                threshold.direction(),
                label + " " + percent(value) + "，未超过警告阈值 " + percent(threshold.warnValue()) + "。"));
      }
    }

    String level;
    if (critical) {
      level = LEVEL_CRITICAL;
    } else if (warning) {
      level = LEVEL_WARNING;
    } else if (known == METRIC_CODES.size()) {
      level = LEVEL_HEALTHY;
    } else {
      level = LEVEL_UNKNOWN;
    }

    return new Evaluation(level, known, breaches, List.copyOf(reasons), List.copyOf(unknown), worstMargin);
  }

  /** 越大越危险 ⇒ value ≥ threshold；越小越危险 ⇒ value ≤ threshold。 */
  static boolean exceeds(BigDecimal value, BigDecimal threshold, String direction) {
    if (DIRECTION_BELOW_IS_BAD.equals(direction)) {
      return value.compareTo(threshold) <= 0;
    }
    return value.compareTo(threshold) >= 0;
  }

  /** 越线幅度（相对阈值的比例），用于同等级内的排序。 */
  static BigDecimal margin(BigDecimal value, BigDecimal threshold, String direction) {
    BigDecimal raw;
    if (DIRECTION_BELOW_IS_BAD.equals(direction)) {
      raw = threshold.subtract(value);
    } else {
      raw = value.subtract(threshold);
    }
    if (raw.signum() <= 0) {
      return BigDecimal.ZERO;
    }
    if (threshold.signum() == 0) {
      return raw.setScale(6, RoundingMode.HALF_UP);
    }
    return raw.divide(threshold, 6, RoundingMode.HALF_UP);
  }

  /** 等级排序权重（数字越小越靠前）。 */
  public static int levelRank(String level) {
    switch (level == null ? "" : level) {
      case LEVEL_CRITICAL:
        return 0;
      case LEVEL_WARNING:
        return 1;
      case LEVEL_UNKNOWN:
        return 2;
      case LEVEL_HEALTHY:
        return 3;
      default:
        return 4;
    }
  }

  /** 等级的人话。 */
  public static String levelLabel(String level) {
    switch (level == null ? "" : level) {
      case LEVEL_CRITICAL:
        return "严重（先处理）";
      case LEVEL_WARNING:
        return "预警";
      case LEVEL_HEALTHY:
        return "健康";
      case LEVEL_UNKNOWN:
        return "判不了（指标不全）";
      default:
        return level;
    }
  }

  /** 阈值方向的人话。 */
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

  // ================================================================ 排序

  /**
   * 🔴 跨店铺风险排序的比较器。
   *
   * <p>次序（先处理最可能掉号的店铺）：
   *
   * <ol>
   *   <li>等级：CRITICAL → WARNING → UNKNOWN → HEALTHY。</li>
   *   <li>同级比命中阈值的指标数（多的先）。</li>
   *   <li>再比最大越线幅度（越线越狠的先）。</li>
   *   <li>最后按店铺编码稳定排序（保证分页不会因为并列而抖动）。</li>
   * </ol>
   *
   * <p>🔴 UNKNOWN 排在 HEALTHY 前面：量不全的店铺**还不知道**会不会掉号，
   * 而量全且没命中阈值的店铺是**确凿健康** —— 后者显然更不需要先处理。
   */
  public static Comparator<RankedShop> ranking() {
    return Comparator.<RankedShop>comparingInt(s -> levelRank(s.evaluation().riskLevel()))
        .thenComparing(Comparator.comparingInt((RankedShop s) -> s.evaluation().breachCount()).reversed())
        .thenComparing(
            Comparator.comparing((RankedShop s) -> s.evaluation().worstMargin()).reversed())
        .thenComparing(s -> s.shopCode() == null ? "" : s.shopCode());
  }

  /**
   * 参与排序的一家店。
   *
   * @param shopId 店铺 id
   * @param shopCode 店铺编码
   * @param shopName 店铺名
   * @param platformCode 平台编码
   * @param evaluation 判定结果
   */
  public record RankedShop(
      String shopId, String shopCode, String shopName, String platformCode, Evaluation evaluation) {}
}
