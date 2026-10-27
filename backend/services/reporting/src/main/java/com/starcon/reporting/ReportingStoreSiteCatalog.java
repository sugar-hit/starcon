package com.starcon.reporting;

import java.util.List;

/**
 * 13.2.2.1 店铺与站点的**指标口径**（口径唯一的「标准份」）。
 *
 * <p>⚠️ **口径唯一**：一个指标只允许一处定义，其他地方引用不允许重算。
 * 本类是**标准口径的默认值**（第一次投影时按租户播种一份到
 * {@code reporting_store_site_metric_definition}），而**运行时唯一的真相是那张表** ——
 * 租户改了口径，查询就跟着改；查询 SQL 里**不允许**出现任何硬编码的指标公式。
 *
 * <p>⚠️ **播种顺序有意义**：派生指标的**被比较指标**必须**先存在**（DB 触发器
 * {@code reporting_store_site_metric_definition_ref} 会拒绝口径不成立的定义），
 * 所以列表里派生指标永远排在它依赖的指标之后。
 *
 * <p><b>🔴 与 13.2.1.1（销量趋势）的关系</b>：两边读的是**同一批上游订单**
 * （9 订单的订单总额 + 平台单回挂店铺），但**口径码不重名**（本表一律 {@code STORE_SITE_} 前缀）
 * ⇒ 同一个数不会有两个真相。本模块多出来的是三件事：
 *
 * <ol>
 *   <li><b>站点维度</b>（13.2.1.1 只有 STORE / PRODUCT / TOTAL 三种维度）；
 *   <li><b>店铺级销量（件数）</b>（13.2.1.1 的店铺维度只有金额与订单数，销量只落在商品维度）；
 *   <li><b>增长贡献度</b>（谁的增量算在总增量里）与<b>新店 / 老店区分</b>。
 * </ol>
 *
 * <p><b>🔴 三种聚合方式的分母各不相同</b>（这是本模块最容易算错的地方）：
 *
 * <ul>
 *   <li>{@code GROUP_SHARE} —— 分母是**同层级合计**：选店铺维度时是「全部店铺的合计」，
 *       选平台维度时是「全部平台的合计」。层级由调用方决定 ⇒ 分母只能在**查询期**算。
 *   <li>{@code GROUP_CONTRIBUTION} —— 分子是**该维度的增量**（当前窗口值 − 基期窗口值），
 *       分母是**全部维度的增量之和**。总增量为负时贡献度照样是负数（它回答的是
 *       「这次掉的量里，各家各掉多少」）。
 *   <li>{@code WINDOW_GROWTH} —— 分子是当前窗口值，分母是**上一等长窗口**的同一个指标。
 *       基期缺失或为 0 ⇒ **算不出**（不是 0%）。
 * </ul>
 */
public final class ReportingStoreSiteCatalog {

  private ReportingStoreSiteCatalog() {}

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
    /** 组内占比：该维度取值 ÷ 同层级合计。 */
    GROUP_SHARE,
    /** 增长贡献度：该维度的增量 ÷ 全部维度的增量之和。 */
    GROUP_CONTRIBUTION,
    /** 窗口环比：(当前窗口值 − 上一等长窗口值) ÷ 上一等长窗口值。 */
    WINDOW_GROWTH
  }

  /** 拆解维度（**互为上下级**：平台 → 站点 → 店铺）。 */
  public enum DimensionType {
    /** 店铺维度。 */
    SHOP,
    /** 站点维度（平台的某个站点，如 Amazon US）。 */
    SITE,
    /** 平台维度（渠道）。 */
    PLATFORM
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
  // 基础口径（流量型）
  // ---------------------------------------------------------------------------

  /** 成交金额（**不跨币种相加**，按币种分别报）。 */
  public static final String STORE_SITE_SALES_AMOUNT = "STORE_SITE_SALES_AMOUNT";
  /** 销量（件数）。 */
  public static final String STORE_SITE_SALES_QUANTITY = "STORE_SITE_SALES_QUANTITY";
  /** 成交订单数（**按订单去重**：一张订单里同一款 SKU 出现两行也只算一单）。 */
  public static final String STORE_SITE_ORDER_COUNT = "STORE_SITE_ORDER_COUNT";

  // ---------------------------------------------------------------------------
  // 派生口径
  // ---------------------------------------------------------------------------

  /** 成交金额占比 = 该维度成交金额 ÷ 同层级合计成交金额。 */
  public static final String STORE_SITE_AMOUNT_SHARE = "STORE_SITE_AMOUNT_SHARE";
  /** 销量占比 = 该维度销量 ÷ 同层级合计销量。 */
  public static final String STORE_SITE_QUANTITY_SHARE = "STORE_SITE_QUANTITY_SHARE";
  /** 成交金额环比增长率。 */
  public static final String STORE_SITE_AMOUNT_GROWTH = "STORE_SITE_AMOUNT_GROWTH";
  /** 销量环比增长率。 */
  public static final String STORE_SITE_QUANTITY_GROWTH = "STORE_SITE_QUANTITY_GROWTH";
  /** 成交金额增长贡献度 = 该维度的金额增量 ÷ 全部维度的金额增量之和。 */
  public static final String STORE_SITE_AMOUNT_GROWTH_CONTRIBUTION =
      "STORE_SITE_AMOUNT_GROWTH_CONTRIBUTION";
  /** 销量增长贡献度 = 该维度的销量增量 ÷ 全部维度的销量增量之和。 */
  public static final String STORE_SITE_QUANTITY_GROWTH_CONTRIBUTION =
      "STORE_SITE_QUANTITY_GROWTH_CONTRIBUTION";
  /**
   * 新店增长贡献度 = **新店**的金额增量 ÷ 全部店铺的金额增量之和。
   *
   * <p>🔴 **它不是某一个维度对象的指标**，而是「新店这一组」的指标 ——
   * 所以它**不出现在分组报表的每一行里**，只出现在「新店 / 老店」汇总块里。
   */
  public static final String STORE_SITE_NEW_STORE_AMOUNT_CONTRIBUTION =
      "STORE_SITE_NEW_STORE_AMOUNT_CONTRIBUTION";

  /**
   * 标准口径清单。
   *
   * <p>⚠️ 顺序即播种顺序：派生指标必须排在其依赖之后（DB 触发器会校验）。
   */
  public static final List<Definition> STANDARD =
      List.of(
          new Definition(
              STORE_SITE_SALES_AMOUNT,
              "成交金额",
              Unit.CURRENCY,
              Aggregation.SUM,
              null,
              true,
              "该店铺（或其所属站点 / 平台）下成交订单的**订单总额**之和，"
                  + "按平台单回挂的 shop_code 归属到店铺。"
                  + "⚠️ **挂不上店铺的订单不计入任何店铺**（平台单缺 sales_order_id 关联时），"
                  + "所以店铺合计可能小于公司口径合计；**按币种分别统计，绝不跨币种相加**。"
                  + "🔴 与 13.2.1.1 销量趋势的店铺成交金额**同源同口径**（同一批订单、同一归属规则），"
                  + "差别只在本模块多了站点 / 平台维度树与增长贡献。口径归属：9 订单（trade）。",
              "9.1.1.1"),
          new Definition(
              STORE_SITE_SALES_QUANTITY,
              "销量（件）",
              Unit.QUANTITY,
              Aggregation.SUM,
              null,
              false,
              "该店铺（或其所属站点 / 平台）下成交订单**明细行的数量**之和。"
                  + "🔴 **件数与货币无关** ⇒ 报告时给出一个跨币种合计（币种为空串 = 与币种无关，"
                  + "不是「币种未知」）；事实仍按币种分行存储。"
                  + "⚠️ 销量只存在于订单明细里 ⇒ 必须逐单取明细（有张数上限），"
                  + "触到上限时如实报「已截断」，**不悄悄少算**。"
                  + "口径归属：9 订单明细（trade sales_order_line）。",
              "9.1.1.1"),
          new Definition(
              STORE_SITE_ORDER_COUNT,
              "成交订单数",
              Unit.COUNT,
              Aggregation.SUM,
              null,
              false,
              "该店铺（或其所属站点 / 平台）下的**成交订单笔数**（按订单去重："
                  + "一张订单里同一款 SKU 出现两行也只算一单）。"
                  + "与件数同理：单数与货币无关 ⇒ 跨币种合计（币种为空串）。"
                  + "口径归属：9 订单列表（trade）。",
              "9.1.1.1"),
          // ---- 派生口径（依赖上面已登记的指标）----
          new Definition(
              STORE_SITE_AMOUNT_SHARE,
              "成交金额占比",
              Unit.PERCENT,
              Aggregation.GROUP_SHARE,
              STORE_SITE_SALES_AMOUNT,
              true,
              "成交金额占比 = 该维度的成交金额 ÷ **同层级合计**成交金额。"
                  + "层级由调用方选择决定：选店铺维度时分母是全部店铺的合计，"
                  + "选平台维度时是全部平台的合计。"
                  + "⚠️ 分母只含**能唯一归属到该层级**的部分 —— 站点维度里「无法唯一归属到站点」"
                  + "的销量不摊给任何站点（摊给谁是造假），那一部分单列。"
                  + "分母为 0 时算不出（不是 0%）。口径归属：13.2.2.1 店铺与站点（本服务派生）。",
              "13.2.2.1"),
          new Definition(
              STORE_SITE_QUANTITY_SHARE,
              "销量占比",
              Unit.PERCENT,
              Aggregation.GROUP_SHARE,
              STORE_SITE_SALES_QUANTITY,
              false,
              "销量占比 = 该维度的销量 ÷ **同层级合计**销量。"
                  + "分母只含能唯一归属到该层级的部分；分母为 0 时算不出（不是 0%）。"
                  + "口径归属：13.2.2.1 店铺与站点（本服务派生）。",
              "13.2.2.1"),
          new Definition(
              STORE_SITE_AMOUNT_GROWTH,
              "成交金额环比增长率",
              Unit.PERCENT,
              Aggregation.WINDOW_GROWTH,
              STORE_SITE_SALES_AMOUNT,
              true,
              "成交金额环比增长率 = (当前窗口值 − 上一等长窗口值) ÷ 上一等长窗口值，"
                  + "被比较的指标是「成交金额」。上一等长窗口为 0 或没有事实时算不出（不是 0%）。"
                  + "口径归属：13.2.2.1 店铺与站点（本服务派生）。",
              "13.2.2.1"),
          new Definition(
              STORE_SITE_QUANTITY_GROWTH,
              "销量环比增长率",
              Unit.PERCENT,
              Aggregation.WINDOW_GROWTH,
              STORE_SITE_SALES_QUANTITY,
              false,
              "销量环比增长率 = (当前窗口值 − 上一等长窗口值) ÷ 上一等长窗口值，"
                  + "被比较的指标是「销量（件）」。基期缺失或为 0 时算不出（不是 0%）。"
                  + "口径归属：13.2.2.1 店铺与站点（本服务派生）。",
              "13.2.2.1"),
          new Definition(
              STORE_SITE_AMOUNT_GROWTH_CONTRIBUTION,
              "成交金额增长贡献度",
              Unit.PERCENT,
              Aggregation.GROUP_CONTRIBUTION,
              STORE_SITE_SALES_AMOUNT,
              true,
              "成交金额增长贡献度 = 该维度的**金额增量** ÷ **全部维度的金额增量之和**。"
                  + "🔴 这就是蓝图关系句说的「增长贡献度比绝对值有用」—— "
                  + "它回答的是「这次的增量是哪个店带来的」。"
                  + "⚠️ 总增量为负时贡献度**照样是负数**（它回答的是「这次掉的量里各家各掉多少」）；"
                  + "总增量为 0 时算不出（不是 0%）。"
                  + "口径归属：13.2.2.1 店铺与站点（本服务派生）。",
              "13.2.2.1"),
          new Definition(
              STORE_SITE_QUANTITY_GROWTH_CONTRIBUTION,
              "销量增长贡献度",
              Unit.PERCENT,
              Aggregation.GROUP_CONTRIBUTION,
              STORE_SITE_SALES_QUANTITY,
              false,
              "销量增长贡献度 = 该维度的**销量增量** ÷ **全部维度的销量增量之和**。"
                  + "总增量为负时照样是负数；总增量为 0 时算不出（不是 0%）。"
                  + "口径归属：13.2.2.1 店铺与站点（本服务派生）。",
              "13.2.2.1"),
          new Definition(
              STORE_SITE_NEW_STORE_AMOUNT_CONTRIBUTION,
              "新店增长贡献度",
              Unit.PERCENT,
              Aggregation.GROUP_CONTRIBUTION,
              STORE_SITE_SALES_AMOUNT,
              true,
              "新店增长贡献度 = **新店**（当前窗口有成交、基期没有任何成交事实的店铺）的"
                  + "成交金额增量 ÷ **全部店铺**的成交金额增量之和。"
                  + "🔴 它回答的是「这次的增量里有多少是新店带来的」—— "
                  + "只给绝对值没用，增量归属才是老板要看的。"
                  + "⚠️ 这个指标是「新店这一组」的指标，**不出现在分组报表的每一行里**，"
                  + "只出现在「新店 / 老店」汇总块里。"
                  + "口径归属：13.2.2.1 店铺与站点（本服务派生）。",
              "13.2.2.1"));

  /** 按指标码取标准定义（取不到返回 null —— 调用方据此说「该指标未登记」）。 */
  public static Definition standard(String metricCode) {
    for (var definition : STANDARD) {
      if (definition.metricCode().equals(metricCode)) return definition;
    }
    return null;
  }

  // ---------------------------------------------------------------------------
  // 店铺年龄（新店 / 老店）
  // ---------------------------------------------------------------------------

  /** 新店：当前窗口有成交，**基期没有任何成交事实**。 */
  public static final String STORE_AGE_NEW = "NEW";

  /** 老店：基期就有成交事实。 */
  public static final String STORE_AGE_EXISTING = "EXISTING";

  /**
   * 新店 / 老店的判据说明（页面上原样显示）。
   *
   * <p>🔴 **判据是事实，不是档案**：一家店「是不是新店」由**它有没有在基期卖过**决定，
   * 而不是由它的创建时间或站点启用时间决定 —— 站点启用时间是**旁证**，
   * 因为「站点开了但一直没出单」在经营上仍然是新店。
   */
  public static final String STORE_AGE_RULE =
      "新店 / 老店按**成交事实**判定：当前窗口有成交、而**上一等长窗口没有任何成交事实** ⇒ 新店；"
          + "基期就有成交 ⇒ 老店。⚠️ 站点启用时间（channel 的 shop_marketplace.enabled_at）"
          + "只是**旁证** —— 站点开了但一直没出单，在经营上仍然是新店。";
}
