package com.starcon.reporting;

import java.util.ArrayList;
import java.util.List;

/**
 * 13.2.1.1 销量趋势的**指标口径**（口径唯一的「标准份」）。
 *
 * <p>⚠️ **口径唯一**：一个指标只允许一处定义，其他地方引用不允许重算。
 * 本类是**标准口径的默认值**（第一次投影时按租户播种一份到
 * {@code reporting_sales_metric_definition}），而**运行时唯一的真相是那张表** ——
 * 租户改了口径，查询就跟着改；查询 SQL 里**不允许**出现任何硬编码的指标公式。
 *
 * <p><b>🔴 公司维度（TOTAL）的三个基础口径不在本表登记</b> —— 它们已经登记在
 * 13.1.1.1 的 {@code reporting_metric_definition}（内容唯一来源是
 * {@link ReportingMetricCatalog#STANDARD}）：
 *
 * <ul>
 *   <li>{@link ReportingMetricCatalog#SALES_AMOUNT} —— 成交订单的含税成交金额；
 *   <li>{@link ReportingMetricCatalog#UNIT_SOLD} —— 成交订单行的数量合计；
 *   <li>{@link ReportingMetricCatalog#ORDER_COUNT} —— 成交订单笔数。
 * </ul>
 *
 * ⇒ 本模块**直接引用**这三个码（事实行的 metric_code 就是它们），**不另起一份定义** ——
 * 否则同一个数会有两个口径真相。
 *
 * <p>本表只登记**13.1.1.1 没有的**两类口径：
 *
 * <ol>
 *   <li><b>维度专属口径</b>：店铺维度（STORE）与商品维度（PRODUCT）——
 *       13.1.1.1 的口径表**没有维度列**，且既有表**不允许 ALTER**
 *       （与 13.1.1.2 另起 {@code reporting_store_metric_definition} 同一纪律）；
 *   <li><b>对比口径</b>：环比（PERIOD_OVER_PERIOD）与同比（YEAR_OVER_YEAR）——
 *       13.1.1.1 的 aggregation 枚举里**没有**这两档，
 *       而本模块的核心输出就是趋势点上的**环比与同比**。
 * </ol>
 *
 * <p>⚠️ **播种顺序有意义**：对比口径的被比较指标必须**先存在**
 * （DB 触发器 {@code reporting_sales_metric_definition_ref} 会拒绝口径不成立的定义）：
 * 先播种 13.1.1.1 的公司维度口径（{@link ReportingMetricCatalog#STANDARD}），
 * 再播种本表。
 */
public final class ReportingSalesTrendCatalog {

  private ReportingSalesTrendCatalog() {}

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
    /** 环比：(当前值 − 上一基准值) ÷ 上一基准值。基准缺失或为 0 ⇒ 算不出。 */
    PERIOD_OVER_PERIOD,
    /** 同比：(当前值 − 去年同期值) ÷ 去年同期值。基期缺失或为 0 ⇒ 算不出。 */
    YEAR_OVER_YEAR
  }

  /** 指标所属维度（口径的一部分）。 */
  public enum DimensionType {
    /** 公司维度（全部销售）。 */
    TOTAL,
    /** 店铺维度（按店铺归属分组）。 */
    STORE,
    /** 商品维度（按 SKU 分组）。 */
    PRODUCT
  }

  /** 一个指标的口径定义。 */
  public record Definition(
      String metricCode,
      String displayName,
      Unit unit,
      Aggregation aggregation,
      String baseMetricCode,
      DimensionType dimensionType,
      boolean currencyAware,
      String description,
      String ownerModule) {}

  // ---------------------------------------------------------------------------
  // 口径码
  // ---------------------------------------------------------------------------

  /** 店铺维度成交金额（**与公司口径同一批**销售订单，按店铺归属分组）。 */
  public static final String STORE_SALES_AMOUNT = "STORE_SALES_AMOUNT";
  /** 店铺维度订单量。 */
  public static final String STORE_SALES_ORDER_COUNT = "STORE_SALES_ORDER_COUNT";
  /** 商品维度成交金额（**订单明细行的行金额**之和）。 */
  public static final String PRODUCT_SALES_AMOUNT = "PRODUCT_SALES_AMOUNT";
  /** 商品维度销量（**订单明细行的数量**之和）。 */
  public static final String PRODUCT_SALES_QUANTITY = "PRODUCT_SALES_QUANTITY";

  /** 环比指标码的后缀。 */
  public static final String PERIOD_OVER_PERIOD_SUFFIX = "_PERIOD_OVER_PERIOD";
  /** 同比指标码的后缀。 */
  public static final String YEAR_OVER_YEAR_SUFFIX = "_YEAR_OVER_YEAR";

  /** 环比指标码（由被比较指标推出）。 */
  public static String periodOverPeriodCode(String baseMetricCode) {
    return baseMetricCode + PERIOD_OVER_PERIOD_SUFFIX;
  }

  /** 同比指标码（由被比较指标推出）。 */
  public static String yearOverYearCode(String baseMetricCode) {
    return baseMetricCode + YEAR_OVER_YEAR_SUFFIX;
  }

  // ---------------------------------------------------------------------------
  // 基础口径清单
  // ---------------------------------------------------------------------------

  /**
   * 本模块用到的**全部基础口径**（含 13.1.1.1 已登记的公司维度三个）。
   *
   * <p>⚠️ 前三个**引用** 13.1.1.1 的口径表（不重复定义）；后四个是本表登记的维度专属口径。
   */
  private static final List<Base> BASES =
      List.of(
          new Base(
              ReportingMetricCatalog.SALES_AMOUNT,
              DimensionType.TOTAL,
              "公司口径成交金额",
              Unit.CURRENCY,
              true,
              "成交订单的含税成交金额，按订单币种分别统计，不跨币种相加。"
                  + "⚠️ 本模块**引用** 13.1.1.1 的口径（reporting_metric_definition / "
                  + "ReportingMetricCatalog.SALES_AMOUNT），**不另起一份定义** —— "
                  + "同一个数只能有一个口径真相。口径归属：9.1.1.1 订单列表（trade）。",
              "9.1.1.1"),
          new Base(
              ReportingMetricCatalog.UNIT_SOLD,
              DimensionType.TOTAL,
              "公司口径销量",
              Unit.QUANTITY,
              false,
              "成交订单行的数量合计。⚠️ 它是**行数量口径**，与订单笔数不是一回事"
                  + "（一张订单有多行）；且只统计**取到明细**的订单（明细要逐单取，有上限，"
                  + "刷新结果里会报出截断）。⚠️ 本模块**引用** 13.1.1.1 的口径，不另起一份定义。"
                  + "口径归属：9.1.1.1 订单列表（trade）。",
              "9.1.1.1"),
          new Base(
              ReportingMetricCatalog.ORDER_COUNT,
              DimensionType.TOTAL,
              "公司口径成交订单量",
              Unit.COUNT,
              false,
              "成交订单笔数（按订单去重，不按行拆）。"
                  + "⚠️ 本模块**引用** 13.1.1.1 的口径，不另起一份定义。"
                  + "口径归属：9.1.1.1 订单列表（trade）。",
              "9.1.1.1"),
          new Base(
              STORE_SALES_AMOUNT,
              DimensionType.STORE,
              "店铺成交金额",
              Unit.CURRENCY,
              true,
              "店铺维度成交金额 = **与公司口径同一批**销售订单的订单总额，按平台单回挂的 shop_code 分组。"
                  + "⚠️ **挂不上店铺的订单不计入任何店铺**（平台单缺 sales_order_id 关联时），"
                  + "所以店铺合计可能小于公司口径合计；按币种分别统计，不跨币种相加。"
                  + "口径归属：9 订单（trade 销售订单 + 平台单回挂）。",
              "9.1.1.1"),
          new Base(
              STORE_SALES_ORDER_COUNT,
              DimensionType.STORE,
              "店铺订单量",
              Unit.COUNT,
              false,
              "店铺维度订单量 = 同一批销售订单的笔数，按平台单回挂的 shop_code 分组"
                  + "（挂不上店铺的订单不计入任何店铺）。口径归属：9 订单（trade 销售订单 + 平台单回挂）。",
              "9.1.1.1"),
          new Base(
              PRODUCT_SALES_AMOUNT,
              DimensionType.PRODUCT,
              "商品成交金额",
              Unit.CURRENCY,
              true,
              "商品维度成交金额 = 销售订单**明细行的行金额**之和（trade sales_order_line.line_amount），"
                  + "按 SKU 码分组，按币种分别统计。"
                  + "⚠️ 它与公司口径的订单总额**不是一个数**（订单级折扣 / 运费 / 税不摊到行），"
                  + "所以它是另一个指标码，**不能与公司口径相加**。"
                  + "口径归属：9 订单（trade 销售订单明细）。",
              "9.1.1.1"),
          new Base(
              PRODUCT_SALES_QUANTITY,
              DimensionType.PRODUCT,
              "商品销量",
              Unit.QUANTITY,
              false,
              "商品维度销量 = 销售订单明细行的数量之和，按 SKU 码分组。"
                  + "⚠️ 只统计**取到明细**的订单（明细要逐单取，有上限，刷新结果里会报出截断）。"
                  + "口径归属：9 订单（trade 销售订单明细）。",
              "9.1.1.1"));

  /** 一个基础口径（描述部分由本类给出，单位 / 维度 / 是否涉币一并定下）。 */
  private record Base(
      String metricCode,
      DimensionType dimensionType,
      String displayName,
      Unit unit,
      boolean currencyAware,
      String description,
      String ownerModule) {}

  /** 本模块用到的全部基础口径码。 */
  public static List<String> baseMetricCodes() {
    var out = new ArrayList<String>();
    for (var base : BASES) out.add(base.metricCode());
    return out;
  }

  /** 某维度下的基础口径码。 */
  public static List<String> baseMetricCodes(DimensionType dimensionType) {
    var out = new ArrayList<String>();
    for (var base : BASES) {
      if (base.dimensionType() == dimensionType) out.add(base.metricCode());
    }
    return out;
  }

  /** 基础口径码 → 维度。 */
  public static DimensionType dimensionOf(String metricCode) {
    for (var base : BASES) {
      if (base.metricCode().equals(metricCode)) return base.dimensionType();
    }
    return null;
  }

  /**
   * 标准口径清单（**只含本表登记的那些**）。
   *
   * <p>⚠️ 顺序即播种顺序：对比口径的被比较指标必须**先存在**
   * —— 公司维度的三个基础口径在 13.1.1.1 的表里（先播种那一批），
   * 本表的 STORE / PRODUCT 基础口径在这里。
   */
  public static final List<Definition> STANDARD = build();

  private static List<Definition> build() {
    var out = new ArrayList<Definition>();
    // ---- 本表登记的基础口径：STORE / PRODUCT 维度 ----
    for (var base : BASES) {
      if (base.dimensionType() == DimensionType.TOTAL) continue;
      out.add(
          new Definition(
              base.metricCode(),
              base.displayName(),
              base.unit(),
              Aggregation.SUM,
              null,
              base.dimensionType(),
              base.currencyAware(),
              base.description(),
              base.ownerModule()));
    }
    // ---- 对比口径：每个基础口径两条（环比 / 同比）----
    for (var base : BASES) {
      out.add(
          new Definition(
              periodOverPeriodCode(base.metricCode()),
              base.displayName() + "环比增长率",
              Unit.PERCENT,
              Aggregation.PERIOD_OVER_PERIOD,
              base.metricCode(),
              base.dimensionType(),
              false,
              base.displayName()
                  + "环比增长率 = (当前值 − 上一基准值) ÷ 上一基准值。"
                  + "基准：趋势点是**上一个同粒度桶**（日看昨天、周看上周、月看上月），"
                  + "对比视图是**上一等长窗口**。基准缺失或为 0 时**算不出**（不是 0%）。"
                  + "被比较的指标：" + base.metricCode() + "。口径归属：13.2.1.1 销量趋势（本服务派生）。",
              "13.2.1.1"));
      out.add(
          new Definition(
              yearOverYearCode(base.metricCode()),
              base.displayName() + "同比增长率",
              Unit.PERCENT,
              Aggregation.YEAR_OVER_YEAR,
              base.metricCode(),
              base.dimensionType(),
              false,
              base.displayName()
                  + "同比增长率 = (当前值 − 去年同期值) ÷ 去年同期值。"
                  + "基期缺失或为 0 时**算不出**（不是 0%）。"
                  + "被比较的指标：" + base.metricCode() + "。口径归属：13.2.1.1 销量趋势（本服务派生）。",
              "13.2.1.1"));
    }
    return List.copyOf(out);
  }

  /** 按指标码取标准定义（取不到返回 null —— 调用方据此说「该指标未登记」）。 */
  public static Definition standard(String metricCode) {
    for (var definition : STANDARD) {
      if (definition.metricCode().equals(metricCode)) return definition;
    }
    return null;
  }
}
