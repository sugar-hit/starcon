package com.starcon.reporting;

import java.util.List;

/**
 * 13.3.1.1 产品表现分析的**指标口径目录**（口径唯一的「标准份」）。
 *
 * <p>⚠️ **口径唯一**：一个指标只允许一处定义，其他地方引用不允许重算。
 * 本类是**标准口径的默认值**（第一次投影时按租户播种一份到
 * {@code reporting_product_performance_metric_definition}），而**运行时唯一的真相是那张表** ——
 * 租户改了口径，查询就跟着改；查询 SQL 里**不允许**出现任何硬编码的指标公式。
 *
 * <p>⚠️ **播种顺序有意义**：派生指标的分子分母必须**先存在**
 * （DB 触发器 {@code reporting_product_performance_metric_ref} 会拒绝口径不成立的定义），
 * 所以列表里派生指标永远排在它依赖的指标之后。
 *
 * <p><b>🔴 与 13.2.2.2 的分工</b>：销量 / 销售额 / 订单数的口径登记在
 * {@code reporting_product_metric_definition}（13.2.2.2 的表），本模块**只引用不重复登记** ——
 * 重复登记就是第二个真相。本表只登记**本模块新增的**指标（毛利分子 / 退货 / 流量 / 转化 /
 * 广告 / 库存 / 库龄 / 跟卖价格）。
 *
 * <p><b>🔴 未交付的指标必须显式登记</b>：{@code available = false} + {@code ownerModule} ——
 * 页面上显示「本轮不可用 + 归谁管」，**不是 0**，也不是干脆不出现。
 */
public final class ReportingProductPerformanceCatalog {

  private ReportingProductPerformanceCatalog() {}

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
    /** 派生差：分子 − 分母。 */
    DERIVED_DIFF,
    /** 派生比：分子 ÷ 分母（分母为 0 ⇒ 算不出，不是 0）。 */
    DERIVED_RATIO
  }

  /** 币种口径。 */
  public enum CurrencyScope {
    /** 同币种（默认）：分子分母必须落在同一个币种里。 */
    SAME,
    /** 允许跨币种合计（**只给与货币无关的量**：件数 / 次数）。 */
    ANY
  }

  /** 一个指标的口径定义。 */
  public record Definition(
      String metricCode,
      String displayName,
      Unit unit,
      Aggregation aggregation,
      String numeratorMetricCode,
      String denominatorMetricCode,
      CurrencyScope currencyScope,
      boolean currencyAware,
      boolean available,
      String ownerModule,
      String description,
      String availabilityNote) {}

  // ---------------------------------------------------------------------------
  // 基础口径（流量型）
  // ---------------------------------------------------------------------------

  /** 出库成本（12.4.1.2 成本法出库成本）。 */
  public static final String PRODUCT_COGS_AMOUNT = "PRODUCT_COGS_AMOUNT";
  /** 退货件数（trade 售后单）。 */
  public static final String PRODUCT_RETURNED_QUANTITY = "PRODUCT_RETURNED_QUANTITY";
  /** 退货金额。 */
  public static final String PRODUCT_RETURNED_AMOUNT = "PRODUCT_RETURNED_AMOUNT";
  /** 曝光（平台口径）。 */
  public static final String PRODUCT_IMPRESSIONS = "PRODUCT_IMPRESSIONS";
  /** 点击（平台口径）。 */
  public static final String PRODUCT_CLICKS = "PRODUCT_CLICKS";
  /** Session（平台口径）。 */
  public static final String PRODUCT_SESSIONS = "PRODUCT_SESSIONS";
  /** 平台口径销量（**平台报表口径，不是内部订单**）。 */
  public static final String PRODUCT_PLATFORM_UNITS = "PRODUCT_PLATFORM_UNITS";
  /** 平台口径销售额（**平台报表口径，不是内部订单**）。 */
  public static final String PRODUCT_PLATFORM_SALES_AMOUNT = "PRODUCT_PLATFORM_SALES_AMOUNT";
  /** 广告花费（**活动级**，关联本商品的广告活动）。 */
  public static final String PRODUCT_AD_SPEND = "PRODUCT_AD_SPEND";
  /** 广告曝光（活动级）。 */
  public static final String PRODUCT_AD_IMPRESSIONS = "PRODUCT_AD_IMPRESSIONS";
  /** 广告点击（活动级）。 */
  public static final String PRODUCT_AD_CLICKS = "PRODUCT_AD_CLICKS";
  /** 广告订单（活动级，广告平台口径）。 */
  public static final String PRODUCT_AD_ORDERS = "PRODUCT_AD_ORDERS";
  /** 广告销售额（活动级，广告平台口径）。 */
  public static final String PRODUCT_AD_SALES_AMOUNT = "PRODUCT_AD_SALES_AMOUNT";

  // ---------------------------------------------------------------------------
  // 时点快照口径
  // ---------------------------------------------------------------------------

  /** 在手数量（warehouse 库存余额）。 */
  public static final String PRODUCT_ON_HAND_QUANTITY = "PRODUCT_ON_HAND_QUANTITY";
  /** 预留数量。 */
  public static final String PRODUCT_RESERVED_QUANTITY = "PRODUCT_RESERVED_QUANTITY";
  /** 可用数量。 */
  public static final String PRODUCT_AVAILABLE_QUANTITY = "PRODUCT_AVAILABLE_QUANTITY";
  /** 库龄天数（该维度下**最长**的库龄；档位存在快照的 text_value 里）。 */
  public static final String PRODUCT_AGING_DAYS = "PRODUCT_AGING_DAYS";
  /** 跟卖者价格（**参考指标**，不进自有商品模型）。 */
  public static final String PRODUCT_HIJACKER_PRICE = "PRODUCT_HIJACKER_PRICE";
  /** 自家参考售价（人工维护，非平台权威价）。 */
  public static final String PRODUCT_REFERENCE_PRICE = "PRODUCT_REFERENCE_PRICE";

  // ---------------------------------------------------------------------------
  // 派生口径
  // ---------------------------------------------------------------------------

  /** 毛利额 = 销售额 − 出库成本（同币种）。 */
  public static final String PRODUCT_GROSS_PROFIT_AMOUNT = "PRODUCT_GROSS_PROFIT_AMOUNT";
  /** 毛利率 = 毛利额 ÷ 销售额（同币种）。 */
  public static final String PRODUCT_GROSS_MARGIN = "PRODUCT_GROSS_MARGIN";
  /** 退货率 = 退货件数 ÷ 销量。 */
  public static final String PRODUCT_RETURN_RATE = "PRODUCT_RETURN_RATE";
  /** 点击率 = 点击 ÷ 曝光。 */
  public static final String PRODUCT_CTR = "PRODUCT_CTR";
  /** 转化率 = 平台口径销量 ÷ Session。 */
  public static final String PRODUCT_CONVERSION_RATE = "PRODUCT_CONVERSION_RATE";
  /** 广告点击率 = 广告点击 ÷ 广告曝光。 */
  public static final String PRODUCT_AD_CTR = "PRODUCT_AD_CTR";
  /** 🔴 **商品维度 ACOS —— 本轮不可用**（归 14.3.1.3 商品维度广告效果）。 */
  public static final String PRODUCT_AD_ACOS = "PRODUCT_AD_ACOS";
  /** 🔴 **广告归因 ROAS —— 本轮不可用**（归 14.3.2.1 归因）。 */
  public static final String PRODUCT_AD_ROAS = "PRODUCT_AD_ROAS";

  /**
   * 标准口径清单。
   *
   * <p>⚠️ 顺序即播种顺序：派生指标必须排在其依赖之后（DB 触发器会校验）。
   * ⚠️ 「销售额 / 销量 / 订单数」**不在本清单里** —— 它们登记在 13.2.2.2 的
   * {@code reporting_product_metric_definition}，本模块只引用。
   */
  public static final List<Definition> STANDARD =
      List.of(
          // ---- 基础口径 ----
          new Definition(
              PRODUCT_COGS_AMOUNT,
              "出库成本",
              Unit.CURRENCY,
              Aggregation.SUM,
              null,
              null,
              CurrencyScope.SAME,
              true,
              true,
              "",
              "该商品维度下的**出库成本**合计（12.4.1.2 成本法）。**按币种分别统计，绝不跨币种相加。**"
                  + "🔴 这是**成本法出库成本**，不是 13.4 多模式利润口径 —— "
                  + "它**不含**平台费 / 头程 / 物流费 / 广告费等其它成本项。",
              ""),
          new Definition(
              PRODUCT_RETURNED_QUANTITY,
              "退货件数",
              Unit.QUANTITY,
              Aggregation.SUM,
              null,
              null,
              CurrencyScope.ANY,
              false,
              true,
              "",
              "该商品维度下的**售后退货件数**（9 售后单，按 SKU 归集）。"
                  + "件数与货币无关 ⇒ 跨币种合计（currency 为空 = 与币种无关，不是「币种未知」）。",
              ""),
          new Definition(
              PRODUCT_RETURNED_AMOUNT,
              "退货金额",
              Unit.CURRENCY,
              Aggregation.SUM,
              null,
              null,
              CurrencyScope.SAME,
              true,
              true,
              "",
              "该商品维度下的**售后退货金额**（9 售后单的申请金额）。**按币种分别统计。**",
              ""),
          new Definition(
              PRODUCT_IMPRESSIONS,
              "曝光",
              Unit.COUNT,
              Aggregation.SUM,
              null,
              null,
              CurrencyScope.ANY,
              false,
              true,
              "",
              "该商品维度下的**曝光次数**（4.5.1.1 Listing 表现，**平台报表口径**）。"
                  + "🔴 平台口径 ≠ 内部订单口径，页面必须标注来源。次数与货币无关 ⇒ 不带币种。",
              ""),
          new Definition(
              PRODUCT_CLICKS,
              "点击",
              Unit.COUNT,
              Aggregation.SUM,
              null,
              null,
              CurrencyScope.ANY,
              false,
              true,
              "",
              "该商品维度下的**点击次数**（4.5.1.1 Listing 表现，**平台报表口径**）。",
              ""),
          new Definition(
              PRODUCT_SESSIONS,
              "Session",
              Unit.COUNT,
              Aggregation.SUM,
              null,
              null,
              CurrencyScope.ANY,
              false,
              true,
              "",
              "该商品维度下的 **Session 数**（4.5.1.1 Listing 表现，**平台报表口径**）。",
              ""),
          new Definition(
              PRODUCT_PLATFORM_UNITS,
              "平台口径销量",
              Unit.QUANTITY,
              Aggregation.SUM,
              null,
              null,
              CurrencyScope.ANY,
              false,
              true,
              "",
              "**平台报表口径**的销量（4.5.1.1）。🔴 与内部订单口径的「销量」**不是同一个数** —— "
                  + "两者并列展示，页面必须标注来源，不许混算。",
              ""),
          new Definition(
              PRODUCT_PLATFORM_SALES_AMOUNT,
              "平台口径销售额",
              Unit.CURRENCY,
              Aggregation.SUM,
              null,
              null,
              CurrencyScope.SAME,
              true,
              true,
              "",
              "**平台报表口径**的销售额（4.5.1.1）。🔴 与内部订单口径的「销售额」**不是同一个数**。",
              ""),
          new Definition(
              PRODUCT_AD_SPEND,
              "广告花费（活动级）",
              Unit.CURRENCY,
              Aggregation.SUM,
              null,
              null,
              CurrencyScope.SAME,
              true,
              true,
              "",
              "**投放了本商品**的广告活动的花费合计（14.3.1.1 活动报表）。"
                  + "🔴 这是**活动级**花费，不是这个商品分摊到的花费 —— "
                  + "channel 未交付商品维度广告报表（14.3.1.3）与归因（14.3.2.1），"
                  + "所以这里**不提供** SKU/SPU/Listing 粒度的 ACOS / ROAS。",
              ""),
          new Definition(
              PRODUCT_AD_IMPRESSIONS,
              "广告曝光（活动级）",
              Unit.COUNT,
              Aggregation.SUM,
              null,
              null,
              CurrencyScope.ANY,
              false,
              true,
              "",
              "**投放了本商品**的广告活动的曝光合计（活动级）。",
              ""),
          new Definition(
              PRODUCT_AD_CLICKS,
              "广告点击（活动级）",
              Unit.COUNT,
              Aggregation.SUM,
              null,
              null,
              CurrencyScope.ANY,
              false,
              true,
              "",
              "**投放了本商品**的广告活动的点击合计（活动级）。",
              ""),
          new Definition(
              PRODUCT_AD_ORDERS,
              "广告订单（活动级）",
              Unit.COUNT,
              Aggregation.SUM,
              null,
              null,
              CurrencyScope.ANY,
              false,
              true,
              "",
              "**投放了本商品**的广告活动的订单合计（活动级，广告平台口径）。",
              ""),
          new Definition(
              PRODUCT_AD_SALES_AMOUNT,
              "广告销售额（活动级）",
              Unit.CURRENCY,
              Aggregation.SUM,
              null,
              null,
              CurrencyScope.SAME,
              true,
              true,
              "",
              "**投放了本商品**的广告活动的销售额合计（活动级，广告平台口径）。",
              ""),
          // ---- 时点快照口径 ----
          new Definition(
              PRODUCT_ON_HAND_QUANTITY,
              "在手数量",
              Unit.QUANTITY,
              Aggregation.SUM,
              null,
              null,
              CurrencyScope.ANY,
              false,
              true,
              "",
              "该商品维度下的**在手库存数量**（7 库存余额，**时点快照**）。"
                  + "🔴 warehouse 侧**没有成本列** ⇒ 库存金额算不出来（不编一个成本价）。",
              ""),
          new Definition(
              PRODUCT_RESERVED_QUANTITY,
              "预留数量",
              Unit.QUANTITY,
              Aggregation.SUM,
              null,
              null,
              CurrencyScope.ANY,
              false,
              true,
              "",
              "该商品维度下的**预留库存数量**（时点快照）。",
              ""),
          new Definition(
              PRODUCT_AVAILABLE_QUANTITY,
              "可用数量",
              Unit.QUANTITY,
              Aggregation.SUM,
              null,
              null,
              CurrencyScope.ANY,
              false,
              true,
              "",
              "该商品维度下的**可用库存数量**（时点快照）。",
              ""),
          new Definition(
              PRODUCT_AGING_DAYS,
              "库龄天数",
              Unit.COUNT,
              Aggregation.SUM,
              null,
              null,
              CurrencyScope.ANY,
              false,
              true,
              "",
              "该商品维度下的**最长库龄天数**（7 库存库龄，时点快照；库龄档位在快照的 text_value 里）。"
                  + "口径：current_date − 入库日（warehouse 侧的库龄基准）。",
              ""),
          new Definition(
              PRODUCT_HIJACKER_PRICE,
              "跟卖者价格",
              Unit.CURRENCY,
              Aggregation.SUM,
              null,
              null,
              CurrencyScope.SAME,
              true,
              true,
              "",
              "**参考指标**：跟卖者在平台上观测到的价格（4.5.2.1 跟卖记录）。"
                  + "🔴 竞品 / 跟卖数据**只做参考指标，不进自有商品模型**（蓝图纪律）。",
              ""),
          new Definition(
              PRODUCT_REFERENCE_PRICE,
              "自家参考售价",
              Unit.CURRENCY,
              Aggregation.SUM,
              null,
              null,
              CurrencyScope.SAME,
              true,
              true,
              "",
              "**参考指标**：自家人工维护的参考售价（4.5.2.1，**非平台权威价**），"
                  + "用来和跟卖者价格比价格差。",
              ""),
          // ---- 派生口径（依赖上面已登记的指标）----
          new Definition(
              PRODUCT_GROSS_PROFIT_AMOUNT,
              "毛利额",
              Unit.CURRENCY,
              Aggregation.DERIVED_DIFF,
              "PRODUCT_SALES_AMOUNT",
              PRODUCT_COGS_AMOUNT,
              CurrencyScope.SAME,
              true,
              true,
              "",
              "毛利额 = **销售额 − 出库成本**（同币种）。"
                  + "🔴 这里的「销售额」是 13.2.2.2 登记的商品维度口径（内部订单口径），"
                  + "「出库成本」是 12.4.1.2 的成本法出库成本 —— "
                  + "**不等于 13.4 多模式利润**（缺平台费 / 头程 / 物流费 / 广告费等）。",
              ""),
          new Definition(
              PRODUCT_GROSS_MARGIN,
              "毛利率",
              Unit.PERCENT,
              Aggregation.DERIVED_RATIO,
              PRODUCT_GROSS_PROFIT_AMOUNT,
              "PRODUCT_SALES_AMOUNT",
              CurrencyScope.SAME,
              true,
              true,
              "",
              "毛利率 = 毛利额 ÷ 销售额（同币种）。分母为 0 时**算不出来**（不是 0%）。",
              ""),
          new Definition(
              PRODUCT_RETURN_RATE,
              "退货率",
              Unit.PERCENT,
              Aggregation.DERIVED_RATIO,
              PRODUCT_RETURNED_QUANTITY,
              "PRODUCT_SALES_QUANTITY",
              CurrencyScope.ANY,
              false,
              true,
              "",
              "退货率 = 退货件数 ÷ 销量（**内部订单口径**）。分母为 0 时**算不出来**（不是 0%）。",
              ""),
          new Definition(
              PRODUCT_CTR,
              "点击率",
              Unit.PERCENT,
              Aggregation.DERIVED_RATIO,
              PRODUCT_CLICKS,
              PRODUCT_IMPRESSIONS,
              CurrencyScope.ANY,
              false,
              true,
              "",
              "点击率 = 点击 ÷ 曝光（平台口径）。分母为 0 时**算不出来**（不是 0%）。",
              ""),
          new Definition(
              PRODUCT_CONVERSION_RATE,
              "转化率",
              Unit.PERCENT,
              Aggregation.DERIVED_RATIO,
              PRODUCT_PLATFORM_UNITS,
              PRODUCT_SESSIONS,
              CurrencyScope.ANY,
              false,
              true,
              "",
              "转化率 = **平台口径销量** ÷ Session（平台口径）。分母为 0 时**算不出来**（不是 0%）。",
              ""),
          new Definition(
              PRODUCT_AD_CTR,
              "广告点击率（活动级）",
              Unit.PERCENT,
              Aggregation.DERIVED_RATIO,
              PRODUCT_AD_CLICKS,
              PRODUCT_AD_IMPRESSIONS,
              CurrencyScope.ANY,
              false,
              true,
              "",
              "广告点击率 = 广告点击 ÷ 广告曝光（活动级）。分母为 0 时**算不出来**（不是 0%）。",
              ""),
          // ---- 🔴 本轮不可用（上游未交付）----
          new Definition(
              PRODUCT_AD_ACOS,
              "商品维度 ACOS",
              Unit.PERCENT,
              Aggregation.DERIVED_RATIO,
              PRODUCT_AD_SPEND,
              PRODUCT_AD_SALES_AMOUNT,
              CurrencyScope.SAME,
              true,
              false,
              "14.3.1.3",
              "ACOS = 广告花费 ÷ 广告销售额（商品维度）。",
              "🔴 **本轮不可用**：channel 的广告报表只有活动 / 平台 / 店铺 / 广告类型 / 币种"
                  + "五种分组维度，**没有 SKU / SPU / Listing 维度** —— "
                  + "商品维度广告效果归 **14.3.1.3**。本模块给出的是**活动级**花费，"
                  + "**不换算**成商品级 ACOS（换算就是编造归因）。"),
          new Definition(
              PRODUCT_AD_ROAS,
              "广告归因 ROAS",
              Unit.CURRENCY,
              Aggregation.DERIVED_RATIO,
              PRODUCT_AD_SALES_AMOUNT,
              PRODUCT_AD_SPEND,
              CurrencyScope.SAME,
              true,
              false,
              "14.3.2.1",
              "ROAS = 广告销售额 ÷ 广告花费（归因口径）。",
              "🔴 **本轮不可用**：广告**归因**（末次点击 / 首次点击）归 **14.3.2.1**，"
                  + "上游未交付 ⇒ 不给归因 ROAS。本模块的广告销售额是**广告平台自己报的口径**，"
                  + "不是归因结果，两者不可混用。"));

  /** 按指标码取标准定义（取不到返回 null —— 调用方据此说「该指标未登记」）。 */
  public static Definition standard(String metricCode) {
    for (var definition : STANDARD) {
      if (definition.metricCode().equals(metricCode)) return definition;
    }
    return null;
  }

  // ---------------------------------------------------------------------------
  // 页面区块（蓝图职责句里的十个指标 + 排名分位）
  // ---------------------------------------------------------------------------

  /** 页面上的一个区块。 */
  public record Section(
      String sectionCode, String title, List<String> metricCodes, String description) {}

  /**
   * 区块清单（**顺序即页面顺序**）。
   *
   * <p>🔴 顺序按「运营看一个产品的心智顺序」排：先看卖了多少、赚了多少，
   * 再看流量与转化从哪来，然后是退货与库存，最后是广告与竞品。
   */
  public static final List<Section> SECTIONS =
      List.of(
          new Section(
              "SALES",
              "销量与销售额",
              List.of("PRODUCT_SALES_QUANTITY", "PRODUCT_SALES_AMOUNT", "PRODUCT_ORDER_COUNT"),
              "内部订单口径（9.1.1.1 订单明细，经 13.2.2.2 投影）。"),
          new Section(
              "GROSS_PROFIT",
              "毛利",
              List.of(PRODUCT_GROSS_PROFIT_AMOUNT, PRODUCT_GROSS_MARGIN, PRODUCT_COGS_AMOUNT),
              "🔴 毛利 = 销售额 − 出库成本（12.4.1.2 成本法），**不是 13.4 多模式利润**。"),
          new Section(
              "TRAFFIC",
              "流量",
              List.of(PRODUCT_IMPRESSIONS, PRODUCT_CLICKS, PRODUCT_SESSIONS, PRODUCT_CTR),
              "🔴 **平台报表口径**（4.5.1.1），与内部订单口径不同，页面标注来源。"),
          new Section(
              "CONVERSION",
              "转化",
              List.of(PRODUCT_CONVERSION_RATE, PRODUCT_PLATFORM_UNITS, PRODUCT_PLATFORM_SALES_AMOUNT),
              "🔴 转化率 = 平台口径销量 ÷ Session（4.5.1.1）。"),
          new Section(
              "RETURNS",
              "退货",
              List.of(PRODUCT_RETURNED_QUANTITY, PRODUCT_RETURNED_AMOUNT, PRODUCT_RETURN_RATE),
              "内部售后单口径（9 售后单，按 SKU 归集）。"),
          new Section(
              "INVENTORY",
              "库存",
              List.of(PRODUCT_ON_HAND_QUANTITY, PRODUCT_RESERVED_QUANTITY, PRODUCT_AVAILABLE_QUANTITY),
              "7 库存余额（**时点快照**）。🔴 warehouse 无成本列 ⇒ 库存金额算不出来。"),
          new Section(
              "AGING",
              "库龄",
              List.of(PRODUCT_AGING_DAYS),
              "7 库存库龄（**时点快照**）：该维度下最长库龄天数 + 档位。"),
          new Section(
              "ADS",
              "广告表现",
              List.of(
                  PRODUCT_AD_SPEND,
                  PRODUCT_AD_IMPRESSIONS,
                  PRODUCT_AD_CLICKS,
                  PRODUCT_AD_ORDERS,
                  PRODUCT_AD_CTR,
                  PRODUCT_AD_ACOS,
                  PRODUCT_AD_ROAS),
              "🔴 **活动级**广告数据（14.3.1.1）+ 活动与商品的关联（14.1.1.2）。"
                  + "商品维度 ACOS / 归因 ROAS **本轮未交付**（14.3.1.3 / 14.3.2.1）。"),
          new Section(
              "COMPETITION",
              "竞品对比",
              List.of(PRODUCT_HIJACKER_PRICE, PRODUCT_REFERENCE_PRICE),
              "🔴 **参考指标**：跟卖者价格 vs 自家参考售价（4.5.2.1）。"
                  + "竞品数据**不进自有商品模型**。"));

  /** 区块清单（供能力声明与前端渲染）。 */
  public static List<Section> sections() {
    return SECTIONS;
  }
}
