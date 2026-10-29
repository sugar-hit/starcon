package com.starcon.reporting;

import java.util.List;

/**
 * 13.2.2.2 SKU/SPU/Listing 的**商品维度指标口径**（口径唯一的「标准份」）。
 *
 * <p>⚠️ **口径唯一**：一个指标只允许一处定义，其他地方引用不允许重算。
 * 本类是**标准口径的默认值**（第一次投影时按租户播种一份到
 * {@code reporting_product_metric_definition}），而**运行时唯一的真相是那张表** ——
 * 租户改了口径，查询就跟着改；查询 SQL 里**不允许**出现任何硬编码的指标公式。
 *
 * <p>⚠️ **播种顺序有意义**：派生指标的分子分母必须**先存在**（DB 触发器
 * {@code reporting_product_metric_definition_ref} 会拒绝口径不成立的定义），
 * 所以列表里派生指标永远排在它依赖的指标之后。
 *
 * <p><b>🔴 三层维度（SKU / SPU / LISTING）的口径差异</b>：
 *
 * <ul>
 *   <li>{@link #PRODUCT_SALES_AMOUNT} / {@link #PRODUCT_SALES_QUANTITY} /
 *       {@link #PRODUCT_ORDER_COUNT} —— 三层都有，**同一套指标码**：
 *       一个 SPU 的销量就是它名下所有 SKU 的销量之和（口径唯一：不是另立一个
 *       {@code SPU_SALES_AMOUNT}，那样「SPU 的成交金额」就有两个定义）；
 *   <li>{@link #PRODUCT_AVG_UNIT_PRICE} / {@link #PRODUCT_ORDER_AVG_AMOUNT} ——
 *       分子分母**同层同币种**（金额除以量，必须同币种）；
 *   <li>{@link #PRODUCT_VARIANT_SHARE} —— 分子在 <b>SKU 层</b>、分母在 <b>SPU 层</b>：
 *       「这个变体占了它所属 SPU 多少销量」。这是全模块唯一**跨层**的口径，
 *       也是蓝图「变体销量分布」的唯一落点。
 * </ul>
 *
 * <p><b>🔴 跨币种只对件数开放</b>：变体销量分布是**件数分布**，件数与货币无关 ⇒
 * {@code currencyScope = ANY}（允许跨币种合计件数）。而「成交金额」「件均价」「客单价」
 * 全部 {@code currencyScope = SAME} —— DB 层的 CHECK 会钉死
 * （{@code currency_aware = true ⇒ currency_scope = 'SAME'}），
 * 所以「绝不跨币种直接相加」这条红线在**数据层**就绕不过去。
 */
public final class ReportingProductSalesCatalog {

  private ReportingProductSalesCatalog() {}

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
    /** 允许跨币种合计（**只给与货币无关的量**：件数 / 单数）。 */
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
      String numeratorDimensionType,
      String denominatorDimensionType,
      CurrencyScope currencyScope,
      boolean currencyAware,
      String description,
      String ownerModule) {}

  // ---------------------------------------------------------------------------
  // 基础口径（流量型，三层通用）
  // ---------------------------------------------------------------------------

  /** 成交金额（**不跨币种相加**，按币种分别报）。 */
  public static final String PRODUCT_SALES_AMOUNT = "PRODUCT_SALES_AMOUNT";
  /** 销量（件数）。 */
  public static final String PRODUCT_SALES_QUANTITY = "PRODUCT_SALES_QUANTITY";
  /** 成交订单数（**按订单去重**：一张订单里同一款 SKU 出现两行也只算一单）。 */
  public static final String PRODUCT_ORDER_COUNT = "PRODUCT_ORDER_COUNT";

  // ---------------------------------------------------------------------------
  // 派生口径
  // ---------------------------------------------------------------------------

  /** 件均价 = 成交金额 ÷ 销量（同层同币种）。 */
  public static final String PRODUCT_AVG_UNIT_PRICE = "PRODUCT_AVG_UNIT_PRICE";
  /** 客单价 = 成交金额 ÷ 成交订单数（同层同币种）。 */
  public static final String PRODUCT_ORDER_AVG_AMOUNT = "PRODUCT_ORDER_AVG_AMOUNT";
  /** 变体销量占比 = 该 SKU 销量 ÷ 它所属 SPU 的销量（**跨层**：分子 SKU 层 / 分母 SPU 层）。 */
  public static final String PRODUCT_VARIANT_SHARE = "PRODUCT_VARIANT_SHARE";

  /**
   * 标准口径清单。
   *
   * <p>⚠️ 顺序即播种顺序：派生指标必须排在其依赖之后（DB 触发器会校验）。
   */
  public static final List<Definition> STANDARD =
      List.of(
          new Definition(
              PRODUCT_SALES_AMOUNT,
              "成交金额",
              Unit.CURRENCY,
              Aggregation.SUM,
              null,
              null,
              null,
              null,
              CurrencyScope.SAME,
              true,
              "该商品维度下成交订单行的成交金额合计。**按币种分别统计，绝不跨币种相加。**"
                  + "SKU 层 = 该 SKU 的订单行金额；SPU 层 = 该 SPU 名下所有 SKU 的金额之和；"
                  + "Listing 层 = 能**唯一归属**到该 Listing 的 SKU 的金额之和。"
                  + "口径归属：9.1.1.1 订单明细（trade sales_order / sales_order_line）。",
              "9.1.1.1"),
          new Definition(
              PRODUCT_SALES_QUANTITY,
              "销量（件）",
              Unit.QUANTITY,
              Aggregation.SUM,
              null,
              null,
              null,
              null,
              CurrencyScope.ANY,
              false,
              "该商品维度下成交的**件数**（订单行数量之和）。"
                  + "🔴 **件数与货币无关** ⇒ 报告时给出一个跨币种合计（currency 为空串 = 与币种无关，"
                  + "不是「币种未知」）；事实仍按币种分行存储，派生指标（件均价 / 客单价）按同币种取数。"
                  + "**金额类指标永远不跨币种相加。**"
                  + "口径归属：9.1.1.1 订单明细（trade sales_order_line）。",
              "9.1.1.1"),
          new Definition(
              PRODUCT_ORDER_COUNT,
              "成交订单数",
              Unit.COUNT,
              Aggregation.SUM,
              null,
              null,
              null,
              null,
              CurrencyScope.ANY,
              false,
              "该商品维度下成交的**订单笔数**（按订单去重：一张订单里同一款 SKU 出现两行也只算一单）。"
                  + "与件数同理：单数与货币无关 ⇒ 跨币种合计（currency 为空串）。"
                  + "口径归属：9.1.1.1 订单列表（trade sales_order）。",
              "9.1.1.1"),
          // ---- 派生口径（依赖上面已登记的指标）----
          new Definition(
              PRODUCT_AVG_UNIT_PRICE,
              "件均价",
              Unit.CURRENCY,
              Aggregation.DERIVED_RATIO,
              PRODUCT_SALES_AMOUNT,
              PRODUCT_SALES_QUANTITY,
              null,
              null,
              CurrencyScope.SAME,
              true,
              "件均价 = 成交金额 ÷ 销量。分子分母**同层同币种**（金额除以量，必须同币种）。"
                  + "分母为 0 时算不出（不是 0）。口径归属：13.2.2.2 SKU/SPU/Listing（本服务派生）。",
              "13.2.2.2"),
          new Definition(
              PRODUCT_ORDER_AVG_AMOUNT,
              "客单价",
              Unit.CURRENCY,
              Aggregation.DERIVED_RATIO,
              PRODUCT_SALES_AMOUNT,
              PRODUCT_ORDER_COUNT,
              null,
              null,
              CurrencyScope.SAME,
              true,
              "客单价 = 成交金额 ÷ 成交订单数。分子分母**同层同币种**。"
                  + "分母为 0 时算不出（不是 0）。口径归属：13.2.2.2 SKU/SPU/Listing（本服务派生）。",
              "13.2.2.2"),
          new Definition(
              PRODUCT_VARIANT_SHARE,
              "变体销量占比",
              Unit.PERCENT,
              Aggregation.DERIVED_RATIO,
              PRODUCT_SALES_QUANTITY,
              PRODUCT_SALES_QUANTITY,
              "SKU",
              "SPU",
              CurrencyScope.ANY,
              false,
              "变体销量占比 = 该 SKU 的销量 ÷ **它所属 SPU** 的销量。"
                  + "分子在 SKU 层、分母在 SPU 层（**全模块唯一跨层口径**）—— "
                  + "不声明这两层的话，SPU 层会算出「SPU 销量 ÷ SPU 销量 = 100%」，"
                  + "那是个看起来对、其实没意义的数。"
                  + "分母（SPU 销量）为 0 时算不出（不是 0%）。"
                  + "⚠️ 占比允许跨币种合计**件数**（件数与货币无关），但金额永远不跨币种相加。"
                  + "口径归属：13.2.2.2 SKU/SPU/Listing（本服务派生）。",
              "13.2.2.2"));

  /** 按指标码取标准定义（取不到返回 null —— 调用方据此说「该指标未登记」）。 */
  public static Definition standard(String metricCode) {
    for (var definition : STANDARD) {
      if (definition.metricCode().equals(metricCode)) return definition;
    }
    return null;
  }
}
