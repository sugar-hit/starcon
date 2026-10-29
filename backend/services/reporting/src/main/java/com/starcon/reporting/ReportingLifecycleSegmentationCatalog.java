package com.starcon.reporting;

import java.util.List;

/**
 * 13.3.1.2 生命周期与分层的**标准口径与标准规则**（分层规则配置的标准份）。
 *
 * <p>⚠️ <b>口径纪律：本模块不登记任何指标口径</b>。分层依据的销量 / 销售额 / 毛利
 * **已经在** 13.2.2.2 的 {@code reporting_product_metric_definition}
 * 与 13.3.1.1 的 {@code reporting_product_performance_metric_definition} 里登记了 ——
 * 本模块只**引用**指标码（{@link #QUANTITY_METRIC_CODE} / {@link #AMOUNT_METRIC_CODE} /
 * {@link #GROSS_PROFIT_METRIC_CODE}），**不另起一张口径表重复登记同一批指标**。
 * 重复登记就是第二个真相：租户改了 13.3.1.1 的毛利口径，本模块若有一份自己的登记，
 * 两边会算出两个不同的毛利，分层结果随即失真。
 *
 * <p>⚠️ <b>规则配置在库里，不在代码里</b>：阈值 / 分类口径 / 版本 / 生效区间全部落在
 * {@code reporting_lifecycle_segmentation_rule}。本类只是**标准份**（第一次分层时按租户播种一份），
 * 运行时唯一的真相是那张表 —— 租户改了阈值，查询就跟着改。
 *
 * <p>⚠️ <b>判定顺序是确定的</b>（页面与接口都照着显示，避免「同一个商品两个人算出不同档」）：
 *
 * <ol>
 *   <li><b>NEW 新品</b> —— 生命周期判定：首次销售距判定基准日 ≤ 新品天数。优先于表现判定，
 *       因为「新品」本身就是一个阶段，不该被「表现规则」重新定义（否则新品永远只能是平款）。</li>
 *   <li><b>DECLINING 衰退款</b> —— 生命周期判定：近窗日均销量相对对比窗日均销量下滑超过阈值。</li>
 *   <li><b>HOT 爆款</b> —— 表现判定：窗口内销量份额 ≥ 爆款门槛。</li>
 *   <li><b>STRONG 旺款</b> —— 表现判定：份额 ≥ 旺款门槛。</li>
 *   <li><b>SLOW 滞销款</b> —— 表现判定：份额低于平款门槛 <b>且</b> 日均销量低于滞销门槛
 *       <b>且</b> 在手库存 &gt; 0（没库存的卖不动不叫滞销）。</li>
 *   <li><b>STEADY 平款</b> —— 其余（表现一般但还没到滞销）。</li>
 * </ol>
 */
public final class ReportingLifecycleSegmentationCatalog {

  private ReportingLifecycleSegmentationCatalog() {}

  // ==========================================================================
  // 引用的口径码（**引用，不登记**）
  // ==========================================================================

  /** 销量（件数）—— 登记在 13.2.2.2 的商品维度口径表。 */
  public static final String QUANTITY_METRIC_CODE = "PRODUCT_SALES_QUANTITY";

  /** 销售额 —— 登记在 13.2.2.2 的商品维度口径表。 */
  public static final String AMOUNT_METRIC_CODE = "PRODUCT_SALES_AMOUNT";

  /** 毛利额 —— 登记在 13.3.1.1 的产品表现口径表（派生口径：销售额 − 出库成本）。 */
  public static final String GROSS_PROFIT_METRIC_CODE = "PRODUCT_GROSS_PROFIT_AMOUNT";

  // ==========================================================================
  // 档位与动作
  // ==========================================================================

  /** 生命周期档位（六档）。 */
  public enum Segment {
    /** 新品。 */
    NEW("新品"),
    /** 爆款。 */
    HOT("爆款"),
    /** 旺款。 */
    STRONG("旺款"),
    /** 平款。 */
    STEADY("平款"),
    /** 滞销款。 */
    SLOW("滞销款"),
    /** 衰退款。 */
    DECLINING("衰退款");

    private final String label;

    Segment(String label) {
      this.label = label;
    }

    /** 中文档位名（页面上显示）。 */
    public String label() {
      return label;
    }
  }

  /**
   * 建议动作（四类：备货 / 清仓 / 下架 / 推广）。
   *
   * <p>⚠️ <b>档位 → 动作的映射是确定的</b>（蓝图输出句「分层结果 + 各层建议（备货/清仓/下架/推广）」）：
   *
   * <ul>
   *   <li>爆款 / 旺款 ⇒ <b>备货</b>（蓝图加粗句「爆款备货、滞销清仓」）；
   *   <li>滞销款 ⇒ <b>清仓</b>；
   *   <li>衰退款 ⇒ <b>下架</b>（还在卖但已经在衰退，卖不动了）；
   *   <li>新品 / 平款 ⇒ <b>推广</b>（新品要流量，平款有稳定销量、值得推一把）。
   * </ul>
   */
  public enum Action {
    /** 备货。 */
    RESTOCK("备货"),
    /** 清仓。 */
    CLEARANCE("清仓"),
    /** 下架。 */
    DELIST("下架"),
    /** 推广。 */
    PROMOTE("推广");

    private final String label;

    Action(String label) {
      this.label = label;
    }

    /** 中文动作名（页面上显示）。 */
    public String label() {
      return label;
    }
  }

  /**
   * 档位 → 建议动作。
   *
   * @return 该档位的建议动作
   */
  public static Action actionOf(Segment segment) {
    return switch (segment) {
      case NEW -> Action.PROMOTE;
      case HOT -> Action.RESTOCK;
      case STRONG -> Action.RESTOCK;
      case STEADY -> Action.PROMOTE;
      case SLOW -> Action.CLEARANCE;
      case DECLINING -> Action.DELIST;
    };
  }

  // ==========================================================================
  // 标准规则（第一次分层时按租户播种）
  // ==========================================================================

  /**
   * 一条规则的标准值。
   *
   * @param dimensionType 分层维度层：SKU / SPU
   */
  public record StandardRule(
      String ruleCode,
      String ruleName,
      String dimensionType,
      int windowDays,
      int newMaxDaysSinceFirstSale,
      int firstSaleLookbackDays,
      int minHistoryDays,
      String decliningMinDeclineRatio,
      String hotMinSalesShareRatio,
      String strongMinSalesShareRatio,
      String steadyMinSalesShareRatio,
      String slowMaxSalesPerDay,
      String abcACumulativeRatio,
      String abcBCumulativeRatio,
      String note) {}

  /**
   * SKU 层标准规则。
   *
   * <p>阈值取值理由（页面上照着显示，别让用户猜这些数字从哪来）：
   *
   * <ul>
   *   <li><b>新品 30 天</b>：上新后第一个月内算新品 —— 超过一个月还没动，运营就该按表现重新看了。
   *   <li><b>首次销售回溯 90 天</b>：只往回看一个季度，找不到第一次销售就**不下新品结论**。
   *   <li><b>衰退下滑 40%</b>：日均销量掉到一半以下才算衰退 —— 抖一抖不算衰退。
   *   <li><b>爆款 10% / 旺款 3% / 平款 0.5%</b>：窗口内销量份额门槛。
   *   <li><b>滞销日均 0.5 件</b>：平均两天卖不到一件，且有库存 ⇒ 滞销。
   *   <li><b>ABC 80% / 95%</b>：经典帕累托切分。
   * </ul>
   *
   * <p>🔴 **比例一律是百分数**（10.0000 = 10%），**不是分数** ——
   * 与 13.3.1.1 的 PERCENT 口径同一条纪律（服务端先乘 100 再除，
   * 先除到 4 位小数再乘 100 会把有效数字截掉）。前端只做展示格式化，不自己乘 100。
   */
  public static final StandardRule DEFAULT_SKU =
      new StandardRule(
          "LIFECYCLE_DEFAULT_SKU",
          "默认分层规则（SKU 层）",
          "SKU",
          30,
          30,
          90,
          1,
          "40.0000",
          "10.0000",
          "3.0000",
          "0.5000",
          "0.5000",
          "80.0000",
          "95.0000",
          "标准分层规则：新品 30 天 / 首次销售回溯 90 天 / 衰退下滑 40% / 爆款 10% / 旺款 3% / 平款 0.5% / 滞销日均 0.5 件 / ABC 80%·95%。");

  /**
   * SPU 层标准规则。
   *
   * <p>⚠️ 与 SKU 层**同一套阈值**：分层讲的是「这个商品活得好不好」，
   * SPU 才是商品的本来身份（同一款在多个 SKU 上算一次），
   * 阈值分两套会让同一个商品在两层上给出不同的结论。
   */
  public static final StandardRule DEFAULT_SPU =
      new StandardRule(
          "LIFECYCLE_DEFAULT_SPU",
          "默认分层规则（SPU 层）",
          "SPU",
          DEFAULT_SKU.windowDays(),
          DEFAULT_SKU.newMaxDaysSinceFirstSale(),
          DEFAULT_SKU.firstSaleLookbackDays(),
          DEFAULT_SKU.minHistoryDays(),
          DEFAULT_SKU.decliningMinDeclineRatio(),
          DEFAULT_SKU.hotMinSalesShareRatio(),
          DEFAULT_SKU.strongMinSalesShareRatio(),
          DEFAULT_SKU.steadyMinSalesShareRatio(),
          DEFAULT_SKU.slowMaxSalesPerDay(),
          DEFAULT_SKU.abcACumulativeRatio(),
          DEFAULT_SKU.abcBCumulativeRatio(),
          DEFAULT_SKU.note());

  /** 标准规则清单。 */
  public static final List<StandardRule> STANDARD = List.of(DEFAULT_SKU, DEFAULT_SPU);

  /**
   * 某个维度层的标准规则。
   *
   * @param dimensionType SKU / SPU
   * @return 标准规则（找不到返回 null）
   */
  public static StandardRule standardOf(String dimensionType) {
    for (var rule : STANDARD) {
      if (rule.dimensionType().equals(dimensionType)) return rule;
    }
    return null;
  }
}
