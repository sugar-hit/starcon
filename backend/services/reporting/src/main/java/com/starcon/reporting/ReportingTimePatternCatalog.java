package com.starcon.reporting;

import java.util.List;

/**
 * 13.2.1.2 分时与分周模式的**口径与判定目录**（口径唯一的「标准份」）。
 *
 * <p>蓝图原文（docs/product/capability-blueprint/13-profit-and-analytics.md:121）：
 *
 * <pre>
 *   ### 13.2.1.2 分时与分周模式
 *   - **职责**：分析出单时间分布（按小时、按星期），识别高峰低谷，支持按此调整仓内人力与促销排期。
 *   - **输入**：订单时间戳（按店铺时区换算）；时段配置。
 *   - **输出**：时段分布热力图 + 高峰时段排序。
 *   - **关系**：上游 = 9 订单；下游 = 8.5 作业排班、14 广告（分时投放）。
 *     **跨境多时区店铺必须按各自时区统计**，汇总时区不同会造成误判。
 * </pre>
 *
 * <p><b>🔴 口径唯一</b>：公司维度已有的三个基础口径（{@code SALES_AMOUNT} /
 * {@code UNIT_SOLD} / {@code ORDER_COUNT}，登记在 13.1.1.1 的
 * {@code reporting_metric_definition}）本模块<b>直接引用，不重定义</b> ——
 * 分时 / 分周只是<b>同一个指标换了一种切法</b>（订单总额仍然是订单总额），
 * 不是另一个口径。本类只登记 13.1.1.1 <b>没有</b>的两类口径。
 *
 * <p><b>🔴 时段配置的初始值是「播种」，不是「数据源」</b>：{@link #DEFAULT_SLOTS} 只是
 * 服务层在租户第一次投影时写进 {@code reporting_time_pattern_slot} 的一份初始配置
 * （与 13.1.1.1 / 13.1.1.2 / 13.2.1.1 播种口径同一纪律）。
 * <b>运行时的判定一律读表</b>：某个小时属于哪个时段、高峰低谷怎么判，
 * 全部由 {@code reporting_time_pattern_slot} 的行算出来 —— 改一行配置，判定立刻跟着改。
 */
public final class ReportingTimePatternCatalog {

  private ReportingTimePatternCatalog() {}

  // ---------------------------------------------------------------------------
  // 单位与聚合方式（与本仓其它口径表同一套词表）
  // ---------------------------------------------------------------------------

  /** 单位。 */
  public enum Unit {
    CURRENCY,
    COUNT,
    QUANTITY,
    PERCENT
  }

  /**
   * 聚合方式（本模块自己新增的两档）。
   *
   * <p>⚠️ 13.1.1.1 的 {@code reporting_metric_definition} 里<b>没有</b>这两档
   * ⇒ 本模块另起 {@code reporting_time_pattern_metric_definition} 登记它们，
   * 派生型<b>只写被比较指标的引用</b>（{@code base_metric_code}），不写公式 SQL。
   */
  public enum Aggregation {
    /** 组内占比：该维度取值 ÷ 同一窗口的同层级合计。 */
    GROUP_SHARE,
    /** 分位阈值：取被分位指标的分位点（如 P90）。 */
    PERCENTILE
  }

  /** 一个指标的口径定义。 */
  public record Definition(
      String metricCode,
      String displayName,
      Unit unit,
      Aggregation aggregation,
      String baseMetricCode,
      boolean currencyAware,
      String description,
      String ownerModule) {}

  // ---------------------------------------------------------------------------
  // 本模块新增的指标码
  // ---------------------------------------------------------------------------

  /** 时段订单占比 = 该时段订单数 ÷ 同一窗口全部时段订单数。 */
  public static final String TIME_PATTERN_SLOT_ORDER_SHARE = "TIME_PATTERN_SLOT_ORDER_SHARE";

  /** 时段成交金额占比 = 该时段成交金额 ÷ 同一窗口同币种全部时段成交金额。 */
  public static final String TIME_PATTERN_SLOT_AMOUNT_SHARE = "TIME_PATTERN_SLOT_AMOUNT_SHARE";

  /** 小时订单量 P90 阈值 = 小时订单量分布的 90 分位（数据驱动的高峰刻度）。 */
  public static final String TIME_PATTERN_HOUR_P90_ORDER_COUNT =
      "TIME_PATTERN_HOUR_P90_ORDER_COUNT";

  /**
   * 标准口径清单。
   *
   * <p>⚠️ 顺序即播种顺序：派生 / 分位口径必须排在其依赖之后（DB 触发器会校验）。
   */
  public static final List<Definition> STANDARD =
      List.of(
          new Definition(
              TIME_PATTERN_SLOT_ORDER_SHARE,
              "时段订单占比",
              Unit.PERCENT,
              Aggregation.GROUP_SHARE,
              ReportingMetricCatalog.ORDER_COUNT,
              false,
              "时段订单占比 = 该时段的**订单笔数** ÷ **同一窗口全部时段的订单笔数合计**。"
                  + "🔴 分母只含**能按店铺本地时间归属**的订单 —— 定不了时区的店铺"
                  + "（启用多个不同时区的站点，或没启用站点）不摊给任何时段，"
                  + "那一部分单列（摊给谁是造假）。"
                  + "⚠️ 分母为 0 时**算不出来**（不是 0%）。"
                  + "口径归属：13.2.1.2 分时与分周模式（本服务派生）。",
              "13.2.1.2"),
          new Definition(
              TIME_PATTERN_SLOT_AMOUNT_SHARE,
              "时段成交金额占比",
              Unit.PERCENT,
              Aggregation.GROUP_SHARE,
              ReportingMetricCatalog.SALES_AMOUNT,
              true,
              "时段成交金额占比 = 该时段的**成交金额** ÷ **同一窗口同币种全部时段的成交金额合计**。"
                  + "🔴 **绝不跨币种相加** —— 每个币种各自一个分母。"
                  + "⚠️ 分母为 0 时**算不出来**（不是 0%）。"
                  + "口径归属：13.2.1.2 分时与分周模式（本服务派生）。",
              "13.2.1.2"),
          new Definition(
              TIME_PATTERN_HOUR_P90_ORDER_COUNT,
              "小时订单量 P90 阈值",
              Unit.COUNT,
              Aggregation.PERCENTILE,
              ReportingMetricCatalog.ORDER_COUNT,
              false,
              "小时订单量 P90 阈值 = **24 个小时桶**的订单笔数分布的 **90 分位**"
                  + "（线性插值，与 PostgreSQL 的 percentile_cont 同一定义）。"
                  + "🔴 **24 个桶全部参与**（没有事实的小时算 0）—— "
                  + "只拿有单的小时算分位会把高峰刻度抬高。"
                  + "它是**数据驱动**的高峰刻度，与「时段配置」里租户自己标的层级"
                  + "（PEAK / SHOULDER / TROUGH）是**两回事**，页面上分开显示。"
                  + "口径归属：13.2.1.2 分时与分周模式（本服务派生）。",
              "13.2.1.2"));

  /** 按指标码取标准定义（取不到返回 null —— 调用方据此说「该指标未登记」）。 */
  public static Definition standard(String metricCode) {
    for (var definition : STANDARD) {
      if (definition.metricCode().equals(metricCode)) return definition;
    }
    return null;
  }

  // ---------------------------------------------------------------------------
  // 汇总口径（跨店铺汇总时对齐的是哪一个槽位）
  // ---------------------------------------------------------------------------

  /**
   * 跨店铺汇总的对齐口径。
   *
   * <p>🔴 「各店铺本地 17:00」和「UTC 17:00」<b>不是同一批订单</b>，
   * 两个口径的结果<b>不同</b> —— 选了哪个必须写在响应里，不能让人以为默认那一个。
   */
  public enum Alignment {
    /**
     * **本地时间对齐（默认）**：「小时 17」= 每个店铺<b>各自</b>的本地 17:00–17:59。
     *
     * <p>选它的理由：蓝图的下游是 <b>8.5 作业排班</b>与 <b>14 广告（分时投放）</b> ——
     * 这两件事问的都是「<b>买家在什么时间点下单</b>」，而买家的时钟是
     * <b>店铺所在市场的本地时钟</b>；广告后台的分时投放也是按站点当地时间设的。
     */
    LOCAL_TIME,
    /**
     * **UTC 槽位对齐**：「小时 17」= 所有店铺共同的 <b>UTC 17:00–17:59</b>。
     *
     * <p>它的用处是看<b>仓内同一绝对时刻</b>的压力（跨时区团队排班）；
     * 但它<b>不能</b>回答「我们的买家什么时候下单」。
     */
    UTC_SLOT
  }

  /** 汇总口径的展示名。 */
  public static String alignmentLabel(Alignment alignment) {
    return alignment == Alignment.UTC_SLOT ? "UTC 槽位对齐" : "店铺本地时间对齐";
  }

  /**
   * 汇总口径的说明（页面上原样显示，让用户看得懂「汇总是按哪个口径」）。
   */
  public static String alignmentNote(Alignment alignment) {
    if (alignment == Alignment.UTC_SLOT) {
      return "🔴 当前口径 = **UTC 槽位对齐**：「小时 17」指的是所有店铺共同的 UTC 17:00–17:59。"
          + "它回答的是「仓内同一绝对时刻的压力」，**不**回答「买家什么时候下单」。";
    }
    return "🔴 当前口径 = **店铺本地时间对齐（默认）**：「小时 17」指的是**每个店铺各自**的"
        + "本地 17:00–17:59。选它的理由：蓝图的下游是 8.5 作业排班与 14 广告（分时投放），"
        + "问的都是「买家在什么时间点下单」，而买家的时钟是**店铺所在市场的本地时钟**。"
        + "⚠️ 与「UTC 槽位对齐」**结果不同** —— 需要看仓内同一绝对时刻的压力时可切换。";
  }

  // ---------------------------------------------------------------------------
  // 时段层级与判定
  // ---------------------------------------------------------------------------

  /** 租户配置的时段层级：高峰 / 平峰 / 低谷。 */
  public enum Level {
    PEAK,
    SHOULDER,
    TROUGH
  }

  /** 层级的展示名。 */
  public static String levelLabel(String level) {
    if (level == null) return "";
    return switch (level) {
      case "PEAK" -> "高峰";
      case "SHOULDER" -> "平峰";
      case "TROUGH" -> "低谷";
      default -> level;
    };
  }

  /** 判定基准。 */
  public enum QualifyBasis {
    /** 按订单占比（0..1 的小数）。 */
    ORDER_SHARE,
    /** 按订单笔数。 */
    ORDER_COUNT
  }

  /** 判定方向。 */
  public enum QualifyComparison {
    /** 至少达到阈值。 */
    AT_LEAST,
    /** 至多达到阈值。 */
    AT_MOST
  }

  /** 一条时段配置的**初始值**（播种用；运行时判定读表，不读这里）。 */
  public record SlotSeed(
      String slotCode,
      String displayName,
      int startHour,
      int endHour,
      Level level,
      QualifyBasis qualifyBasis,
      QualifyComparison qualifyComparison,
      String qualifyThreshold,
      int sortOrder,
      String description) {}

  /**
   * 🔴 **初始时段配置（播种用，不是数据源）**。
   *
   * <p>⚠️ 这四条**完整覆盖 0..24 小时**（4 × 6 小时）——
   * 本表的触发器要求启用时段**不留空洞**，所以一套初始配置必须一次成型。
   * ⚠️ 租户随时可以改（增删改查端点都在），改完判定立刻跟着改。
   */
  public static final List<SlotSeed> DEFAULT_SLOTS =
      List.of(
          new SlotSeed(
              "DAWN",
              "凌晨",
              0,
              6,
              Level.TROUGH,
              QualifyBasis.ORDER_SHARE,
              QualifyComparison.AT_MOST,
              "0.10",
              10,
              "本地 00:00–05:59。默认按低谷配置：订单占比至多 10% 时它确实是个低谷。"),
          new SlotSeed(
              "MORNING",
              "上午",
              6,
              12,
              Level.PEAK,
              QualifyBasis.ORDER_SHARE,
              QualifyComparison.AT_LEAST,
              "0.25",
              20,
              "本地 06:00–11:59。默认按高峰配置：订单占比至少 25% 时它确实是高峰。"),
          new SlotSeed(
              "AFTERNOON",
              "下午",
              12,
              18,
              Level.PEAK,
              QualifyBasis.ORDER_SHARE,
              QualifyComparison.AT_LEAST,
              "0.20",
              30,
              "本地 12:00–17:59。默认按高峰配置：订单占比至少 20% 时它确实是高峰。"),
          new SlotSeed(
              "EVENING",
              "晚间",
              18,
              24,
              Level.SHOULDER,
              QualifyBasis.ORDER_SHARE,
              QualifyComparison.AT_LEAST,
              "0.15",
              40,
              "本地 18:00–23:59。默认按平峰配置：订单占比至少 15% 时它确实是平峰。"));

  // ---------------------------------------------------------------------------
  // 星期与小时的展示口径
  // ---------------------------------------------------------------------------

  /**
   * 🔴 **ISO 星期几**：1 = 周一 … 7 = 周日。
   *
   * <p>⚠️ 这里的「星期几」是<b>店铺本地时区</b>的星期几（本地周一 00:00 到本地周日 23:59:59），
   * <b>不是</b> ISO 周的<b>周数</b> —— 周数是另一回事，本模块不产出周数。
   */
  public static String weekdayLabel(int weekday) {
    return switch (weekday) {
      case 1 -> "周一";
      case 2 -> "周二";
      case 3 -> "周三";
      case 4 -> "周四";
      case 5 -> "周五";
      case 6 -> "周六";
      case 7 -> "周日";
      default -> "";
    };
  }

  /** 小时标签（本地 24 小时制，左闭右开区间的起点）。 */
  public static String hourLabel(int hour) {
    return String.format("%02d:00", hour);
  }

  /** 区间边界说明（左闭右开）。 */
  public static final String BOUNDARY_NOTE =
      "时段区间是**左闭右开** `[start_hour, end_hour)`：start_hour 那一小时算在内，"
          + "end_hour 那一小时**不算**（例：`[9, 12)` = 09:00、10:00、11:00 三个小时，不含 12:00）。"
          + "`end_hour = 24` 表示「一直到当天 23:59」。";
}
