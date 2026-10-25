package com.starcon.reporting;

import java.util.List;

/**
 * 13.1.1.2 多店对比的**店铺维度指标口径**（口径唯一的「标准份」）。
 *
 * <p>⚠️ **口径唯一**：一个指标只允许一处定义，其他地方引用不允许重算。
 * 本类是**标准口径的默认值**（第一次投影时按租户播种一份到
 * {@code reporting_store_metric_definition}），而**运行时唯一的真相是那张表** ——
 * 租户改了口径，查询就跟着改；查询 SQL 里**不允许**出现任何硬编码的指标公式。
 *
 * <p>⚠️ **播种顺序有意义**：派生指标的分子分母必须**先存在**（DB 触发器
 * {@code reporting_store_metric_definition_ref} 会拒绝口径不成立的定义），
 * 所以列表里派生指标永远排在它依赖的指标之后。
 *
 * <p><b>🔴 跨平台口径差异是这一版的重点</b>（蓝图 13.1.1.2 关系句）：
 * 「亚马逊的 GMV 与 TikTok 的口径不同，对比时标注口径说明」。
 * ⇒ 本模块**同时落两份成交金额**：
 *
 * <ul>
 *   <li>{@link #STORE_GMV_PLATFORM} —— **平台侧**报告的成交金额（各平台各自的口径）；
 *   <li>{@link #STORE_GMV_RESTORED} —— 我方从平台事件**还原**出来的成交金额；
 *   <li>{@link #STORE_CALIBER_GAP} —— 两者之差（DERIVED_DIFF），
 *       这就是「口径差异额」，跨平台对比时它会被原样标出来。
 * </ul>
 */
public final class ReportingStoreComparisonCatalog {

  private ReportingStoreComparisonCatalog() {}

  /** 单位。 */
  public enum Unit {
    CURRENCY,
    COUNT,
    QUANTITY,
    PERCENT,
    /** 倍数（如广告产出比 3.2 表示每 1 元花费带来 3.2 元广告销售额）。 */
    RATIO
  }

  /** 聚合方式。 */
  public enum Aggregation {
    /** 流量型：窗口内把贡献行相加。 */
    SUM,
    /** 存量型：取窗口内最近一天的快照值。 */
    LAST,
    /** 派生差：分子 − 分母。 */
    DERIVED_DIFF,
    /** 派生比：分子 ÷ 分母（分母为 0 ⇒ 算不出，不是 0）。 */
    DERIVED_RATIO,
    /** 窗口环比：(当前窗口值 − 上一等长窗口值) ÷ 上一等长窗口值。 */
    WINDOW_GROWTH
  }

  /** 一个指标的口径定义。 */
  public record Definition(
      String metricCode,
      String displayName,
      Unit unit,
      Aggregation aggregation,
      String numeratorMetricCode,
      String denominatorMetricCode,
      String baseMetricCode,
      boolean currencyAware,
      String description,
      String ownerModule) {}

  // ---------------------------------------------------------------------------
  // 基础口径（流量型）
  // ---------------------------------------------------------------------------

  /** 平台侧成交金额（平台口径）。 */
  public static final String STORE_GMV_PLATFORM = "STORE_GMV_PLATFORM";
  /** 还原口径成交金额（我方口径）。 */
  public static final String STORE_GMV_RESTORED = "STORE_GMV_RESTORED";
  /** 成交订单量。 */
  public static final String STORE_ORDER_COUNT = "STORE_ORDER_COUNT";
  /** 退货额。 */
  public static final String STORE_RETURN_AMOUNT = "STORE_RETURN_AMOUNT";
  /** 广告花费。 */
  public static final String STORE_AD_SPEND = "STORE_AD_SPEND";
  /** 广告销售额（广告归因口径的销售额，**不是**店铺总销售额）。 */
  public static final String STORE_AD_SALES = "STORE_AD_SALES";

  // ---------------------------------------------------------------------------
  // 派生口径
  // ---------------------------------------------------------------------------

  /** 平台口径差异额 = 平台侧成交金额 − 还原口径成交金额。 */
  public static final String STORE_CALIBER_GAP = "STORE_CALIBER_GAP";
  /** 退货率 = 退货额 ÷ 平台侧成交金额。 */
  public static final String STORE_RETURN_RATE = "STORE_RETURN_RATE";
  /** 广告产出比 = 广告销售额 ÷ 广告花费。 */
  public static final String STORE_AD_ROAS = "STORE_AD_ROAS";
  /** 广告花费占比 = 广告花费 ÷ 广告销售额。 */
  public static final String STORE_AD_ACOS = "STORE_AD_ACOS";
  /** 客单价 = 平台侧成交金额 ÷ 成交订单量。 */
  public static final String STORE_GMV_PER_ORDER = "STORE_GMV_PER_ORDER";
  /** 成交金额环比增长率。 */
  public static final String STORE_GMV_GROWTH = "STORE_GMV_GROWTH";
  /** 成交订单量环比增长率。 */
  public static final String STORE_ORDER_GROWTH = "STORE_ORDER_GROWTH";

  /**
   * 标准口径清单。
   *
   * <p>⚠️ 顺序即播种顺序：派生指标必须排在其依赖之后（DB 触发器会校验）。
   */
  public static final List<Definition> STANDARD =
      List.of(
          new Definition(
              STORE_GMV_PLATFORM,
              "平台侧成交金额",
              Unit.CURRENCY,
              Aggregation.SUM,
              null,
              null,
              null,
              true,
              "平台侧报告的成交金额（含平台口径的优惠与运费处理），按币种分别统计，不跨币种相加。"
                  + "各平台口径不同（亚马逊与 TikTok 的 GMV 定义不一样），对比时必须标注。"
                  + "口径归属：9.1.1.1 平台单列表（trade platform_order）。",
              "9.1.1.1"),
          new Definition(
              STORE_GMV_RESTORED,
              "还原口径成交金额",
              Unit.CURRENCY,
              Aggregation.SUM,
              null,
              null,
              null,
              true,
              "我方从平台订单事件还原出来的成交金额（与平台报告口径可能相差分毫），按币种分别统计。"
                  + "口径归属：9.1.1.1 平台单列表（trade platform_order）。",
              "9.1.1.1"),
          new Definition(
              STORE_ORDER_COUNT,
              "成交订单量",
              Unit.COUNT,
              Aggregation.SUM,
              null,
              null,
              null,
              false,
              "带店铺归属的成交订单笔数（按平台单去重）。口径归属：9.1.1.1 平台单列表（trade）。",
              "9.1.1.1"),
          new Definition(
              STORE_RETURN_AMOUNT,
              "退货额",
              Unit.CURRENCY,
              Aggregation.SUM,
              null,
              null,
              null,
              true,
              "归属到店铺的售后退货申请金额（按订单号回挂店铺；挂不上的不计入），按币种分别统计。"
                  + "口径归属：11.1.1.1 售后单列表（trade）。",
              "11.1.1.1"),
          new Definition(
              STORE_AD_SPEND,
              "广告花费",
              Unit.CURRENCY,
              Aggregation.SUM,
              null,
              null,
              null,
              true,
              "店铺名下的平台广告花费合计，按币种分别统计。口径归属：14.3.1.1 活动报表（channel）。",
              "14.3.1.1"),
          new Definition(
              STORE_AD_SALES,
              "广告销售额",
              Unit.CURRENCY,
              Aggregation.SUM,
              null,
              null,
              null,
              true,
              "广告归因口径的广告销售额（**不是**店铺总销售额），按币种分别统计。"
                  + "口径归属：14.3.1.1 活动报表（channel）。",
              "14.3.1.1"),
          // ---- 派生口径（依赖上面已登记的指标）----
          new Definition(
              STORE_CALIBER_GAP,
              "口径差异额",
              Unit.CURRENCY,
              Aggregation.DERIVED_DIFF,
              STORE_GMV_PLATFORM,
              STORE_GMV_RESTORED,
              null,
              true,
              "口径差异额 = 平台侧成交金额 − 还原口径成交金额。它量化了「这个平台的 GMV 口径"
                  + "与我们理解的差多少」—— 跨平台对比时这个数必须一起看，否则会把口径差读成经营差。"
                  + "口径归属：13.1.1.2 多店对比（本服务派生）。",
              "13.1.1.2"),
          new Definition(
              STORE_RETURN_RATE,
              "退货率",
              Unit.PERCENT,
              Aggregation.DERIVED_RATIO,
              STORE_RETURN_AMOUNT,
              STORE_GMV_PLATFORM,
              null,
              true,
              "退货率 = 退货额 ÷ 平台侧成交金额。分母为 0 时算不出（不是 0%）。"
                  + "口径归属：13.1.1.2 多店对比（本服务派生）。",
              "13.1.1.2"),
          new Definition(
              STORE_AD_ROAS,
              "广告产出比",
              Unit.RATIO,
              Aggregation.DERIVED_RATIO,
              STORE_AD_SALES,
              STORE_AD_SPEND,
              null,
              true,
              "广告产出比 = 广告销售额 ÷ 广告花费（倍数）。分母为 0 时算不出（不是 0）。"
                  + "口径归属：13.1.1.2 多店对比（本服务派生）。",
              "13.1.1.2"),
          new Definition(
              STORE_AD_ACOS,
              "广告花费占比",
              Unit.PERCENT,
              Aggregation.DERIVED_RATIO,
              STORE_AD_SPEND,
              STORE_AD_SALES,
              null,
              true,
              "广告花费占比 = 广告花费 ÷ 广告销售额（= ACOS）。分母为 0 时算不出（不是 0%）。"
                  + "口径归属：13.1.1.2 多店对比（本服务派生）。",
              "13.1.1.2"),
          new Definition(
              STORE_GMV_PER_ORDER,
              "客单价",
              Unit.CURRENCY,
              Aggregation.DERIVED_RATIO,
              STORE_GMV_PLATFORM,
              STORE_ORDER_COUNT,
              null,
              true,
              "客单价 = 平台侧成交金额 ÷ 成交订单量。分母为 0 时算不出（不是 0）。"
                  + "口径归属：13.1.1.2 多店对比（本服务派生）。",
              "13.1.1.2"),
          new Definition(
              STORE_GMV_GROWTH,
              "成交金额环比增长率",
              Unit.PERCENT,
              Aggregation.WINDOW_GROWTH,
              null,
              null,
              STORE_GMV_PLATFORM,
              true,
              "成交金额环比增长率 = (当前窗口值 − 上一等长窗口值) ÷ 上一等长窗口值，"
                  + "被比较的指标是「平台侧成交金额」。上一等长窗口为 0 或没有事实时算不出（不是 0%）。"
                  + "口径归属：13.1.1.2 多店对比（本服务派生）。",
              "13.1.1.2"),
          new Definition(
              STORE_ORDER_GROWTH,
              "成交订单量环比增长率",
              Unit.PERCENT,
              Aggregation.WINDOW_GROWTH,
              null,
              null,
              STORE_ORDER_COUNT,
              false,
              "成交订单量环比增长率 = (当前窗口值 − 上一等长窗口值) ÷ 上一等长窗口值，"
                  + "被比较的指标是「成交订单量」。上一等长窗口为 0 或没有事实时算不出（不是 0%）。"
                  + "口径归属：13.1.1.2 多店对比（本服务派生）。",
              "13.1.1.2"));

  /** 按指标码取标准定义（取不到返回 null —— 调用方据此说「该指标未登记」）。 */
  public static Definition standard(String metricCode) {
    for (var definition : STANDARD) {
      if (definition.metricCode().equals(metricCode)) return definition;
    }
    return null;
  }
}
