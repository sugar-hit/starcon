package com.starcon.reporting;

import java.math.BigDecimal;
import java.util.List;

/**
 * 13.3.2.1 流量转化的**指标口径**（口径唯一的「标准份」）。
 *
 * <p>⚠️ **口径唯一**：一个指标只允许一处定义，其他地方引用不允许重算。
 * 本类是**标准口径的默认值**（第一次投影时按租户播种一份到
 * {@code reporting_traffic_conversion_metric_definition}），而**运行时唯一的真相是那张表** ——
 * 租户改了口径，查询就跟着改；查询 SQL 里**不允许**出现任何硬编码的指标公式。
 *
 * <p><b>🔴 本模块的第一性纪律：两套口径不许混算</b>
 *
 * <p>蓝图关系句的加粗部分：「<b>平台流量数据与内部订单数据口径不同</b>，展示时必须标注来源与统计范围
 * （页面 vs 订单）」。所以每个指标都带两个标签：
 *
 * <ul>
 *   <li>{@code caliber} —— <b>PLATFORM</b>（平台口径 / 页面口径）或 <b>ORDER</b>（订单口径）；
 *   <li>{@code trafficSource} —— <b>LISTING</b>（平台商品口径）/ <b>AD</b>（广告口径）/
 *       <b>ORDER</b>（订单口径）。
 * </ul>
 *
 * <p>⇒ 报表里「转化率」有**两个**（平台口径的「成交件数 ÷ Session」、广告口径的「成交订单 ÷ 点击」），
 * 「客单价」有**三个**（平台口径 / 广告口径 / 订单口径）。它们**不是同一个数**，页面上每个都带口径标签。
 *
 * <p><b>🔴 自然流量是推导值，不是平台直接给的数</b>
 *
 * <p>平台侧**没有**「按流量来源拆分」的流量口径（channel 的 listing 表现与广告报表是两套独立报表）。
 * 本模块用 {@code DERIVED_DIFF}（平台商品口径 − 广告口径）给出**残余自然流量**：
 *
 * <ul>
 *   <li>它是**推导值**，口径说明里写明推导方式；
 *   <li>相减为负 ⇒ 两套口径的统计范围不一致 ⇒ 服务层报「口径冲突」，**绝不截成 0**；
 *   <li>自然 **Session 算不出来**（广告侧没有 Session 口径）⇒ 那一格是「不可比」，不是 0。
 * </ul>
 */
public final class ReportingTrafficConversionCatalog {

  private ReportingTrafficConversionCatalog() {}

  /** 单位。 */
  public enum Unit {
    CURRENCY,
    COUNT,
    QUANTITY,
    PERCENT
  }

  /** 聚合方式。 */
  public enum Aggregation {
    /** 流量型：窗口内把贡献行相加。 */
    SUM,
    /** 派生比：分子 ÷ 分母。 */
    DERIVED_RATIO,
    /** 派生差：分子 − 分母（**跨来源**：自然流量 = 平台商品口径 − 广告口径）。 */
    DERIVED_DIFF
  }

  /** 口径来源（**页面上必须显示**）。 */
  public enum Caliber {
    /** 平台口径（页面口径）：平台回报的曝光 / 点击 / Session / 成交。 */
    PLATFORM,
    /** 订单口径：内部订单的笔数与金额。 */
    ORDER
  }

  /** 流量来源。 */
  public enum TrafficSource {
    /** 平台商品口径（2 同步的平台表现：listing 级曝光 / 点击 / Session）。 */
    LISTING,
    /** 广告口径（14 广告的活动报表：广告活动的曝光 / 点击 / 订单）。 */
    AD,
    /** 订单口径（9 订单：内部订单笔数与金额）。 */
    ORDER
  }

  /** 统计日的日历口径（**跨口径比较时必须知道它**）。 */
  public enum BucketBasis {
    /** 平台自然日（平台自己给的统计日）。 */
    PLATFORM_DAY,
    /** 内部订单按 UTC 自然日归集。 */
    UTC_DAY
  }

  /** 一个指标的口径定义。 */
  public record Definition(
      String metricCode,
      String displayName,
      Unit unit,
      Aggregation aggregation,
      String numeratorMetricCode,
      String denominatorMetricCode,
      Caliber caliber,
      TrafficSource trafficSource,
      boolean currencyAware,
      String description,
      String ownerModule) {

    /** 来源口径的名字（**派生指标为空** —— 它的来源由分子分母决定）。 */
    public String trafficSourceName() {
      return trafficSource == null ? null : trafficSource.name();
    }
  }

  // ---------------------------------------------------------------------------
  // 平台商品口径（LISTING）—— 来自 2 同步的平台表现（channel listing-performance）
  // ---------------------------------------------------------------------------

  /** 曝光（**平台口径**）。 */
  public static final String IMPRESSIONS = "TRAFFIC_IMPRESSIONS";
  /** 点击（**平台口径**）。 */
  public static final String CLICKS = "TRAFFIC_CLICKS";
  /** Session（**平台口径**）。 */
  public static final String SESSIONS = "TRAFFIC_SESSIONS";
  /** 成交件数（**平台口径**，非内部订单）。 */
  public static final String PLATFORM_UNITS = "TRAFFIC_PLATFORM_UNITS";
  /** 成交金额（**平台口径**，非内部订单）。 */
  public static final String PLATFORM_SALES_AMOUNT = "TRAFFIC_PLATFORM_SALES_AMOUNT";

  // ---------------------------------------------------------------------------
  // 广告口径（AD）—— 来自 14 广告的活动报表（channel ad-reports）
  // ---------------------------------------------------------------------------

  /** 曝光（**广告口径**）。 */
  public static final String AD_IMPRESSIONS = "TRAFFIC_AD_IMPRESSIONS";
  /** 点击（**广告口径**）。 */
  public static final String AD_CLICKS = "TRAFFIC_AD_CLICKS";
  /** 成交订单（**广告口径**）。 */
  public static final String AD_ORDERS = "TRAFFIC_AD_ORDERS";
  /** 广告花费（**广告口径**）。 */
  public static final String AD_SPEND = "TRAFFIC_AD_SPEND";
  /** 成交金额（**广告口径**）。 */
  public static final String AD_SALES_AMOUNT = "TRAFFIC_AD_SALES_AMOUNT";

  // ---------------------------------------------------------------------------
  // 订单口径（ORDER）—— 来自 9 订单（trade 销售订单）
  // ---------------------------------------------------------------------------

  /** 成交订单数（**订单口径**）。 */
  public static final String ORDER_COUNT = "TRAFFIC_ORDER_COUNT";
  /** 成交金额（**订单口径**）。 */
  public static final String ORDER_AMOUNT = "TRAFFIC_ORDER_AMOUNT";

  // ---------------------------------------------------------------------------
  // 派生口径
  // ---------------------------------------------------------------------------

  /** 点击率 = 点击 ÷ 曝光（**平台商品口径**）。 */
  public static final String CTR = "TRAFFIC_CTR";
  /** 转化率 = 成交件数 ÷ Session（**平台商品口径**）。 */
  public static final String CONVERSION_RATE = "TRAFFIC_CONVERSION_RATE";
  /** 客单价 = 成交金额 ÷ 成交件数（**平台口径**）。 */
  public static final String PLATFORM_AOV = "TRAFFIC_PLATFORM_AOV";
  /** 点击率 = 点击 ÷ 曝光（**广告口径**）。 */
  public static final String AD_CTR = "TRAFFIC_AD_CTR";
  /** 转化率 = 成交订单 ÷ 点击（**广告口径**）。 */
  public static final String AD_CONVERSION_RATE = "TRAFFIC_AD_CONVERSION_RATE";
  /** 单次点击成本 = 花费 ÷ 点击（**广告口径**）。 */
  public static final String AD_CPC = "TRAFFIC_AD_CPC";
  /** 客单价 = 成交金额 ÷ 成交订单（**广告口径**）。 */
  public static final String AD_AOV = "TRAFFIC_AD_AOV";
  /** 客单价 = 内部订单金额 ÷ 内部订单笔数（**订单口径**）。 */
  public static final String ORDER_AOV = "TRAFFIC_ORDER_AOV";
  /** 曝光（**自然流量，推导值**）= 平台商品口径曝光 − 广告口径曝光。 */
  public static final String ORGANIC_IMPRESSIONS = "TRAFFIC_ORGANIC_IMPRESSIONS";
  /** 点击（**自然流量，推导值**）= 平台商品口径点击 − 广告口径点击。 */
  public static final String ORGANIC_CLICKS = "TRAFFIC_ORGANIC_CLICKS";

  /**
   * 标准口径清单。
   *
   * <p>⚠️ 顺序即播种顺序：派生指标必须排在其依赖之后（DB 触发器会校验）。
   */
  public static final List<Definition> STANDARD =
      List.of(
          // ---- 平台商品口径（LISTING）----
          new Definition(
              IMPRESSIONS,
              "曝光（平台商品口径）",
              Unit.QUANTITY,
              Aggregation.SUM,
              null,
              null,
              Caliber.PLATFORM,
              TrafficSource.LISTING,
              false,
              "平台商品（listing）在平台侧回报的**曝光量**（平台口径，非内部订单）。"
                  + "🔴 平台**没有**按流量来源拆分这批数据 ⇒ 它是该商品的整体流量，"
                  + "**含广告带来的曝光**；自然流量是「平台口径 − 广告口径」的**推导值**。"
                  + "⚠️ 与货币无关 ⇒ 跨币种合计（币种显示为空串 = 与币种无关，不是「币种未知」）。"
                  + "口径归属：2 同步的平台表现（channel listing-performance）。",
              "2.1.2.1"),
          new Definition(
              CLICKS,
              "点击（平台商品口径）",
              Unit.QUANTITY,
              Aggregation.SUM,
              null,
              null,
              Caliber.PLATFORM,
              TrafficSource.LISTING,
              false,
              "平台商品（listing）在平台侧回报的**点击量**（平台口径，非内部订单）。"
                  + "🔴 与曝光同源同范围 ⇒ 平台口径的点击率 = 点击 ÷ 曝光 才是同口径的比率。"
                  + "口径归属：2 同步的平台表现（channel listing-performance）。",
              "2.1.2.1"),
          new Definition(
              SESSIONS,
              "Session（平台商品口径）",
              Unit.QUANTITY,
              Aggregation.SUM,
              null,
              null,
              Caliber.PLATFORM,
              TrafficSource.LISTING,
              false,
              "平台商品（listing）在平台侧回报的 **Session 数**（平台口径，非内部订单）。"
                  + "🔴 **内部订单没有 Session** ⇒ 订单口径的转化率**算不出来**（分母缺失不是 0）。"
                  + "口径归属：2 同步的平台表现（channel listing-performance）。",
              "2.1.2.1"),
          new Definition(
              PLATFORM_UNITS,
              "成交件数（平台口径）",
              Unit.QUANTITY,
              Aggregation.SUM,
              null,
              null,
              Caliber.PLATFORM,
              TrafficSource.LISTING,
              false,
              "平台侧回报的**成交件数**（**平台口径，非内部订单**）：平台按平台自然日归集、"
                  + "含取消与部分退款。🔴 它与内部订单笔数**天然对不上**（统计范围不同），"
                  + "报表里两个口径**分列**，差值由「口径背离」诊断规则报出来。"
                  + "口径归属：2 同步的平台表现（channel listing-performance）。",
              "2.1.2.1"),
          new Definition(
              PLATFORM_SALES_AMOUNT,
              "成交金额（平台口径）",
              Unit.CURRENCY,
              Aggregation.SUM,
              null,
              null,
              Caliber.PLATFORM,
              TrafficSource.LISTING,
              true,
              "平台侧回报的**成交金额**（**平台口径，非内部订单**）。"
                  + "🔴 **绝不跨币种相加**：按币种分别报；平台没给币种的金额**不落库**"
                  + "（算进某个币种是编造）。口径归属：2 同步的平台表现（channel listing-performance）。",
              "2.1.2.1"),
          // ---- 广告口径（AD）----
          new Definition(
              AD_IMPRESSIONS,
              "曝光（广告口径）",
              Unit.QUANTITY,
              Aggregation.SUM,
              null,
              null,
              Caliber.PLATFORM,
              TrafficSource.AD,
              false,
              "广告活动在平台侧回报的**曝光量**（**广告口径**：广告报表自己的统计范围）。"
                  + "🔴 广告口径与平台商品口径**不是同一个统计范围** ⇒ 两者相减得到的是"
                  + "**残余自然流量（推导值）**，不是平台给出的自然流量。"
                  + "口径归属：14 广告（channel ad-reports）。",
              "14.3.1.1"),
          new Definition(
              AD_CLICKS,
              "点击（广告口径）",
              Unit.QUANTITY,
              Aggregation.SUM,
              null,
              null,
              Caliber.PLATFORM,
              TrafficSource.AD,
              false,
              "广告活动在平台侧回报的**点击量**（广告口径）。"
                  + "口径归属：14 广告（channel ad-reports）。",
              "14.3.1.1"),
          new Definition(
              AD_ORDERS,
              "成交订单（广告口径）",
              Unit.QUANTITY,
              Aggregation.SUM,
              null,
              null,
              Caliber.PLATFORM,
              TrafficSource.AD,
              false,
              "广告活动回报的**成交订单数**（**广告口径**，平台侧归因到该活动的订单）。"
                  + "🔴 它与平台商品口径的成交件数、内部订单笔数是**三个不同的口径**，"
                  + "报表里分列，不合并。口径归属：14 广告（channel ad-reports）。",
              "14.3.1.1"),
          new Definition(
              AD_SPEND,
              "广告花费（广告口径）",
              Unit.CURRENCY,
              Aggregation.SUM,
              null,
              null,
              Caliber.PLATFORM,
              TrafficSource.AD,
              true,
              "广告活动的**花费**（广告口径）。🔴 绝不跨币种相加（按币种分别报）。"
                  + "口径归属：14 广告（channel ad-reports）。",
              "14.3.1.1"),
          new Definition(
              AD_SALES_AMOUNT,
              "成交金额（广告口径）",
              Unit.CURRENCY,
              Aggregation.SUM,
              null,
              null,
              Caliber.PLATFORM,
              TrafficSource.AD,
              true,
              "广告活动回报的**成交金额**（广告口径，平台侧归因到该活动的销售额）。"
                  + "🔴 绝不跨币种相加。口径归属：14 广告（channel ad-reports）。",
              "14.3.1.1"),
          // ---- 订单口径（ORDER）----
          new Definition(
              ORDER_COUNT,
              "成交订单数（订单口径）",
              Unit.COUNT,
              Aggregation.SUM,
              null,
              null,
              Caliber.ORDER,
              TrafficSource.ORDER,
              false,
              "内部**销售订单**的笔数（订单口径：按本系统单据归集，按 UTC 自然日切桶）。"
                  + "🔴 它与平台口径的成交件数**统计范围不同**（平台含取消/部分退款、平台自然日），"
                  + "两者的差值由「口径背离」诊断规则报出来，**不合并成一个数**。"
                  + "口径归属：9 订单（trade 销售订单列表）。",
              "9.1.1.1"),
          new Definition(
              ORDER_AMOUNT,
              "成交金额（订单口径）",
              Unit.CURRENCY,
              Aggregation.SUM,
              null,
              null,
              Caliber.ORDER,
              TrafficSource.ORDER,
              true,
              "内部**销售订单**的订单总额之和（订单口径）。"
                  + "🔴 绝不跨币种相加；币种缺失的订单**不计入任何币种**。"
                  + "口径归属：9 订单（trade 销售订单列表）。",
              "9.1.1.1"),
          // ---- 派生口径（依赖上面已登记的指标）----
          new Definition(
              CTR,
              "点击率（平台商品口径）",
              Unit.PERCENT,
              Aggregation.DERIVED_RATIO,
              CLICKS,
              IMPRESSIONS,
              Caliber.PLATFORM,
              null,
              false,
              "点击率 = 点击 ÷ 曝光（**平台商品口径**，分子分母同源同范围）。"
                  + "⚠️ 分母为 0 时**算不出来**（不是 0%）。"
                  + "口径归属：13.3.2.1 流量转化（本服务派生）。",
              "13.3.2.1"),
          new Definition(
              CONVERSION_RATE,
              "转化率（平台商品口径）",
              Unit.PERCENT,
              Aggregation.DERIVED_RATIO,
              PLATFORM_UNITS,
              SESSIONS,
              Caliber.PLATFORM,
              null,
              false,
              "转化率 = 成交件数 ÷ Session（**平台口径**，分子分母都是平台回报的数）。"
                  + "🔴 **订单口径的转化率算不出来**（内部订单没有 Session）—— 那一格是「不可比」。"
                  + "⚠️ 分母为 0 时算不出来（不是 0%）。"
                  + "口径归属：13.3.2.1 流量转化（本服务派生）。",
              "13.3.2.1"),
          new Definition(
              PLATFORM_AOV,
              "客单价（平台口径）",
              Unit.CURRENCY,
              Aggregation.DERIVED_RATIO,
              PLATFORM_SALES_AMOUNT,
              PLATFORM_UNITS,
              Caliber.PLATFORM,
              null,
              true,
              "客单价 = 平台成交金额 ÷ 平台成交件数（**平台口径**）。"
                  + "🔴 分子分母必须**同币种**（平台成交金额按币种分列）；"
                  + "🔴 它与订单口径客单价**不是同一个数**（统计范围不同），页面上分列显示。"
                  + "口径归属：13.3.2.1 流量转化（本服务派生）。",
              "13.3.2.1"),
          new Definition(
              AD_CTR,
              "点击率（广告口径）",
              Unit.PERCENT,
              Aggregation.DERIVED_RATIO,
              AD_CLICKS,
              AD_IMPRESSIONS,
              Caliber.PLATFORM,
              null,
              false,
              "点击率 = 广告点击 ÷ 广告曝光（**广告口径**）。"
                  + "⚠️ 分母为 0 时算不出来（不是 0%）。"
                  + "口径归属：13.3.2.1 流量转化（本服务派生）。",
              "13.3.2.1"),
          new Definition(
              AD_CONVERSION_RATE,
              "转化率（广告口径）",
              Unit.PERCENT,
              Aggregation.DERIVED_RATIO,
              AD_ORDERS,
              AD_CLICKS,
              Caliber.PLATFORM,
              null,
              false,
              "转化率 = 广告成交订单 ÷ 广告点击（**广告口径**）。"
                  + "🔴 分子是**平台侧归因到广告活动的订单**，与内部订单笔数不同口径。"
                  + "⚠️ 分母为 0 时算不出来（不是 0%）。"
                  + "口径归属：13.3.2.1 流量转化（本服务派生）。",
              "13.3.2.1"),
          new Definition(
              AD_CPC,
              "单次点击成本（广告口径）",
              Unit.CURRENCY,
              Aggregation.DERIVED_RATIO,
              AD_SPEND,
              AD_CLICKS,
              Caliber.PLATFORM,
              null,
              true,
              "单次点击成本 = 广告花费 ÷ 广告点击（**广告口径**）。"
                  + "🔴 分子分母必须**同币种**。⚠️ 分母为 0 时算不出来。"
                  + "口径归属：13.3.2.1 流量转化（本服务派生）。",
              "13.3.2.1"),
          new Definition(
              AD_AOV,
              "客单价（广告口径）",
              Unit.CURRENCY,
              Aggregation.DERIVED_RATIO,
              AD_SALES_AMOUNT,
              AD_ORDERS,
              Caliber.PLATFORM,
              null,
              true,
              "客单价 = 广告成交金额 ÷ 广告成交订单（**广告口径**）。"
                  + "🔴 分子分母必须**同币种**；与平台口径 / 订单口径客单价**不是同一个数**。"
                  + "口径归属：13.3.2.1 流量转化（本服务派生）。",
              "13.3.2.1"),
          new Definition(
              ORDER_AOV,
              "客单价（订单口径）",
              Unit.CURRENCY,
              Aggregation.DERIVED_RATIO,
              ORDER_AMOUNT,
              ORDER_COUNT,
              Caliber.ORDER,
              null,
              true,
              "客单价 = 内部订单金额 ÷ 内部订单笔数（**订单口径**）。"
                  + "🔴 分子分母必须**同币种**；这是**经营口径的客单价**（平台口径客单价另列）。"
                  + "口径归属：13.3.2.1 流量转化（本服务派生）。",
              "13.3.2.1"),
          new Definition(
              ORGANIC_IMPRESSIONS,
              "曝光（自然流量，推导值）",
              Unit.QUANTITY,
              Aggregation.DERIVED_DIFF,
              IMPRESSIONS,
              AD_IMPRESSIONS,
              Caliber.PLATFORM,
              null,
              false,
              "自然流量曝光 = **平台商品口径曝光 − 广告口径曝光**（**推导值**）。"
                  + "🔴 平台**没有**提供按流量来源拆分的流量数据 ⇒ 这是两套平台口径相减的**残余**，"
                  + "不是平台直接给出的自然流量；相减为负说明两套口径统计范围不一致，"
                  + "该格报「口径冲突」，**绝不截成 0**。"
                  + "口径归属：13.3.2.1 流量转化（本服务派生）。",
              "13.3.2.1"),
          new Definition(
              ORGANIC_CLICKS,
              "点击（自然流量，推导值）",
              Unit.QUANTITY,
              Aggregation.DERIVED_DIFF,
              CLICKS,
              AD_CLICKS,
              Caliber.PLATFORM,
              null,
              false,
              "自然流量点击 = **平台商品口径点击 − 广告口径点击**（**推导值**）。"
                  + "🔴 与自然曝光同源的推导口径；相减为负时报「口径冲突」，不截成 0。"
                  + "口径归属：13.3.2.1 流量转化（本服务派生）。",
              "13.3.2.1"));

  /** 按指标码取标准定义（取不到返回 null —— 调用方据此说「该指标未登记」）。 */
  public static Definition standard(String metricCode) {
    for (var definition : STANDARD) {
      if (definition.metricCode().equals(metricCode)) return definition;
    }
    return null;
  }

  // ==========================================================================
  // 漏斗（每一节的每一格都带自己的口径）
  // ==========================================================================

  /** 漏斗里的一节。 */
  public record FunnelStage(String stageKey, String displayName, String metricCode) {}

  /**
   * 一组漏斗。
   *
   * @param trafficSource 这一组的来源（派生组用 ORGANIC 说明是推导值）
   * @param sourceNote 这一组的来源说明（页面上原样显示）
   */
  public record FunnelGroup(
      String groupKey,
      String displayName,
      String caliber,
      String trafficSource,
      String sourceNote,
      List<FunnelStage> stages) {}

  /** 漏斗分组（**顺序即页面顺序**：平台口径在前，订单口径在后）。 */
  public static final List<FunnelGroup> FUNNEL_GROUPS =
      List.of(
          new FunnelGroup(
              "PLATFORM_LISTING",
              "平台商品口径漏斗",
              Caliber.PLATFORM.name(),
              TrafficSource.LISTING.name(),
              "来自 2 同步的**平台表现**（平台回报的页面口径）：曝光 → 点击 → Session → 成交件数。"
                  + "⚠️ 平台**没有**按流量来源拆分这批数据 ⇒ 这一组是该商品的整体流量（含广告流量）。",
              List.of(
                  new FunnelStage("IMPRESSIONS", "曝光", IMPRESSIONS),
                  new FunnelStage("CLICKS", "点击", CLICKS),
                  new FunnelStage("SESSIONS", "Session", SESSIONS),
                  new FunnelStage("CONVERSION", "成交件数（平台口径）", PLATFORM_UNITS))),
          new FunnelGroup(
              "ORGANIC",
              "自然流量漏斗（推导值）",
              Caliber.PLATFORM.name(),
              "ORGANIC",
              "自然流量 = **平台商品口径 − 广告口径**（两套平台口径相减的**残余**，推导值）。"
                  + "🔴 平台没有提供按流量来源拆分的流量数据 ⇒ 它是推导值，不是平台给出的数；"
                  + "🔴 自然 **Session 算不出来**（广告侧没有 Session 口径）⇒ 那一节是「不可比」。",
              List.of(
                  new FunnelStage("ORGANIC_IMPRESSIONS", "曝光（自然，推导值）", ORGANIC_IMPRESSIONS),
                  new FunnelStage("ORGANIC_CLICKS", "点击（自然，推导值）", ORGANIC_CLICKS))),
          new FunnelGroup(
              "AD",
              "广告口径漏斗",
              Caliber.PLATFORM.name(),
              TrafficSource.AD.name(),
              "来自 14 广告的**活动报表**（广告口径）：曝光 → 点击 → 成交订单。"
                  + "⚠️ 广告口径的成交订单是**平台侧归因到广告活动**的订单，与内部订单笔数不同口径。",
              List.of(
                  new FunnelStage("AD_IMPRESSIONS", "曝光（广告口径）", AD_IMPRESSIONS),
                  new FunnelStage("AD_CLICKS", "点击（广告口径）", AD_CLICKS),
                  new FunnelStage("AD_CONVERSION", "成交订单（广告口径）", AD_ORDERS))),
          new FunnelGroup(
              "ORDER",
              "订单口径成交",
              Caliber.ORDER.name(),
              TrafficSource.ORDER.name(),
              "来自 9 订单的**内部销售订单**（订单口径）：笔数与金额，按 UTC 自然日切桶。"
                  + "🔴 **订单口径没有 Session** ⇒ 订单口径的转化率**算不出来**（分母缺失不是 0）。",
              List.of(new FunnelStage("ORDER_COUNT", "成交订单数（订单口径）", ORDER_COUNT))));

  // ==========================================================================
  // 异常诊断规则（阈值写在这里，页面上照着显示）
  // ==========================================================================

  /** 诊断规则码：曝光断崖（环比下降超过阈值）。 */
  public static final String RULE_IMPRESSION_COLLAPSE = "IMPRESSION_COLLAPSE";
  /** 诊断规则码：点击率骤降。 */
  public static final String RULE_CTR_DROP = "CTR_DROP";
  /** 诊断规则码：转化率骤降。 */
  public static final String RULE_CONVERSION_RATE_DROP = "CONVERSION_RATE_DROP";
  /** 诊断规则码：点击与 Session 口径冲突。 */
  public static final String RULE_SESSION_CLICK_GAP = "SESSION_CLICK_GAP";
  /** 诊断规则码：平台口径与订单口径背离。 */
  public static final String RULE_CALIBER_GAP = "CALIBER_GAP";

  /** 曝光环比下降超过 50% 判为断崖。 */
  public static final BigDecimal IMPRESSION_COLLAPSE_THRESHOLD = new BigDecimal("0.50");
  /** 点击率环比下降超过 30% 判为骤降。 */
  public static final BigDecimal CTR_DROP_THRESHOLD = new BigDecimal("0.30");
  /** 转化率环比下降超过 30% 判为骤降。 */
  public static final BigDecimal CONVERSION_RATE_DROP_THRESHOLD = new BigDecimal("0.30");
  /** 平台口径成交件数与订单口径订单笔数偏差超过 30% 判为口径背离。 */
  public static final BigDecimal CALIBER_GAP_THRESHOLD = new BigDecimal("0.30");

  /** 曝光断崖阈值的人话说明。 */
  public static final String IMPRESSION_COLLAPSE_THRESHOLD_NOTE = "曝光环比下降超过 50%";
  /** 点击率骤降阈值的人话说明。 */
  public static final String CTR_DROP_THRESHOLD_NOTE = "点击率环比下降超过 30%";
  /** 转化率骤降阈值的人话说明。 */
  public static final String CONVERSION_RATE_DROP_THRESHOLD_NOTE = "转化率环比下降超过 30%";
  /** 口径背离阈值的人话说明。 */
  public static final String CALIBER_GAP_THRESHOLD_NOTE = "平台口径成交件数与订单口径订单笔数偏差超过 30%";

  // ==========================================================================
  // 口径说明（页面上常驻显示）
  // ==========================================================================

  /** 口径说明（**每条都写清来源与统计范围** —— 蓝图的硬要求）。 */
  public static final List<String> CALIBER_NOTES =
      List.of(
          "🔴 **平台流量数据与内部订单数据口径不同**，本模块**分列展示、不混算**："
              + "曝光 / 点击 / Session / 平台成交件数与金额是**平台口径**（页面口径，"
              + "平台按平台自然日归集、含平台自己的取消与部分退款口径）；"
              + "内部订单笔数与金额是**订单口径**（本系统单据，按 UTC 自然日切桶）。",
          "🔴 **转化率有两个口径**：平台口径的「成交件数 ÷ Session」与广告口径的"
              + "「广告成交订单 ÷ 广告点击」。订单口径**算不出转化率** —— 内部订单不带 Session，"
              + "分母缺失**不是 0**，那一格显示「不可比」。",
          "🔴 **客单价有三个口径**：平台口径 / 广告口径 / 订单口径。"
              + "经营上常用的「客单价」是**订单口径**（内部订单金额 ÷ 内部订单笔数），"
              + "另两个口径的值也一并给出，但**各自带自己的标签**。",
          "🔴 **自然流量是推导值**：平台**没有**提供按流量来源拆分的流量数据，"
              + "本模块用「平台商品口径 − 广告口径」给出**残余自然流量**；"
              + "相减为负说明两套口径的统计范围不一致，该格报「口径冲突」，**绝不截成 0**。",
          "🔴 **绝不跨币种相加**：金额类指标按币种分别报；只有曝光 / 点击 / Session / 笔数"
              + "这类与货币无关的量给一个跨币种合计，它的币种显示为空串 —— "
              + "空串 = 与币种无关，**不是「币种未知」**。",
          "🔴 **平台口径的统计日是平台自然日**，订单口径的统计日是 **UTC 自然日** —— "
              + "两者**不是同一个日**，跨口径比较时必须记得这一点。",
          "🔴 **数据延迟**：报表是投影，不是实时的。页面上常驻显示**数据更新时间**"
              + "（最近一次投影落库时刻）；空表时是 null（不是一个长得像时间的假值）。"
              + "报表数字与业务系统冲突时，**以业务系统为准**。");
}
