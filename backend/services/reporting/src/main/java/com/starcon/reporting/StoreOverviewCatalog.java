package com.starcon.reporting;

import java.util.List;

/**
 * 1.1.2.2 店铺概览的**维度清单**（蓝图 01 卷 1.1.2.2 原文的逐条落地）。
 *
 * <p>蓝图原文：「按店铺横向对比当期表现（**销售额、订单量、利润率、库存天数、广告花费**），
 * 识别掉队店铺。」本类把那五个维度逐条列成**显式数据**，并回答两个问题：
 *
 * <ul>
 *   <li>这个维度**背后是哪几个店铺级口径码**（{@link DimensionSpec#metricCodes()}）；</li>
 *   <li>这个维度**接不接得通**、接不通**归谁**。</li>
 * </ul>
 *
 * <p>🔴 **口径唯一：维度只声明「引用哪些口径码」，不声明任何公式**。
 * 口径（单位 / 聚合方式 / 分子分母）的运行时唯一真相是
 * {@code reporting_store_metric_definition}（13.1.1.2 多店对比登记的那张表），
 * 本类**不复制一份**；服务层从那张表读，租户改了口径，查询就跟着改。
 *
 * <p>🔴 **五个维度的逐条结论**（这是本模块最核心的验收项，也是最容易做错的地方）：
 *
 * <table border="1">
 *   <caption>蓝图维度 → 店铺级口径码</caption>
 *   <tr><th>维度</th><th>店铺级口径码</th><th>结论</th></tr>
 *   <tr><td>销售额</td><td>STORE_GMV_PLATFORM / STORE_GMV_RESTORED / STORE_CALIBER_GAP</td>
 *       <td>🟡 <b>三个码并列</b>（不是单一口径）</td></tr>
 *   <tr><td>订单量</td><td>STORE_ORDER_COUNT</td><td>✅ 接通</td></tr>
 *   <tr><td>利润率</td><td><b>无</b></td><td>🔴 未接通</td></tr>
 *   <tr><td>库存天数</td><td><b>无</b></td><td>🔴 未接通</td></tr>
 *   <tr><td>广告花费</td><td>STORE_AD_SPEND</td><td>✅ 接通</td></tr>
 * </table>
 *
 * <p>🔴 <b>「销售额」为什么是三个码而不是一个</b>：13.1.1.2 的店铺口径表里
 * <b>没有</b>一个叫「销售额」的码 —— 它登记的是<b>两份成交金额口径</b>
 * （{@code STORE_GMV_PLATFORM} 平台侧 / {@code STORE_GMV_RESTORED} 我方还原）
 * 外加<b>两者的差</b>（{@code STORE_CALIBER_GAP}）。目录既然专门留了这个派生码，
 * 就是要求把口径差异摆出来而不是藏起来 ⇒ 本维度<b>三者并列呈现</b>，
 * <b>不取其一冒充「销售额」</b>（只取一个 = 抹掉口径差异 = 破坏口径唯一）。
 *
 * <p>🔴 <b>租户级 {@code SALES_AMOUNT} 绝对不许拿来冒充店铺级销售额</b>：
 * 那是 1.1.2.1 核心指标卡的<b>租户</b>口径（{@code reporting_metric_definition}），
 * 与本模块的<b>店铺</b>口径（{@code reporting_store_metric_definition}）是两张表、两套维度。
 * 蓝图 1.1.2.1 写死「口径由 13 单点定义，工作台只引用不重算」⇒ 借用租户级码、
 * 或自己写一条店铺级公式，都是在代码里造第二个真相，一律禁止。
 *
 * <p>🔴 <b>接不通的维度不给公式也不给分母</b>（与 1.1.2.1 同纪律）：
 * 给了就是编一个出来，页面上的数字会变成「看起来有依据的假数」。
 */
public final class StoreOverviewCatalog {

  private StoreOverviewCatalog() {}

  /** 维度的取数来源。 */
  public enum Source {
    /** 走 13.1.1.2 已交付的**店铺维度口径**（{@code reporting_store_metric_definition}）。 */
    STORE_METRIC,
    /** 口径尚未接通 —— 维度按「未接通」显示，不给公式也不给分母。 */
    NOT_CONNECTED
  }

  /**
   * 一个维度的静态声明。
   *
   * @param key 维度标识（稳定字符串：它会进工作台布局偏好，改名 = 所有人布局里那一张凭空消失）
   * @param displayName 显示名
   * @param metricCodes 背后引用的**店铺级口径码**（未接通的维度为空列表 —— 没有口径码就没有口径）
   * @param unit 单位（接通的维度以口径表为准；未接通的维度用这里的声明）
   * @param currencyAware 是否涉币（涉币维度**不跨币种相加**）
   * @param source 取数来源
   * @param ownerModule 口径归属模块（接不通时写「这个口径该归谁」）
   * @param caliber 口径说明（页面上原样显示）
   * @param unavailableReason 接不通的原因（{@code source == NOT_CONNECTED} 时必填）
   */
  public record DimensionSpec(
      String key,
      String displayName,
      List<String> metricCodes,
      String unit,
      boolean currencyAware,
      Source source,
      String ownerModule,
      String caliber,
      String unavailableReason) {}

  // ---------------------------------------------------------------------------
  // 维度码常量
  // ---------------------------------------------------------------------------

  /** 销售额（店铺级：两份成交金额口径 + 口径差异额）。 */
  public static final String SALES_AMOUNT = "store.sales-amount";

  /** 订单量。 */
  public static final String ORDER_COUNT = "store.order-count";

  /** 利润率。 */
  public static final String PROFIT_MARGIN = "store.profit-margin";

  /** 库存天数。 */
  public static final String INVENTORY_DAYS = "store.inventory-days";

  /** 广告花费。 */
  public static final String AD_SPEND = "store.ad-spend";

  /**
   * 维度清单（顺序 = 页面上的列顺序）。
   *
   * <p>⚠️ 顺序按蓝图原文排：销售额、订单量、利润率、库存天数、广告花费。
   */
  public static final List<DimensionSpec> DIMENSIONS =
      List.of(
          new DimensionSpec(
              SALES_AMOUNT,
              "销售额",
              List.of(
                  ReportingStoreComparisonCatalog.STORE_GMV_PLATFORM,
                  ReportingStoreComparisonCatalog.STORE_GMV_RESTORED,
                  ReportingStoreComparisonCatalog.STORE_CALIBER_GAP),
              "CURRENCY",
              true,
              Source.STORE_METRIC,
              "9.1.1.1 平台单列表（trade）",
              "🔴 **店铺级没有名为「销售额」的单一口径**：13.1.1.2 登记的是**两份成交金额口径** —— "
                  + "**平台侧成交金额**（各平台各自的口径）与**还原口径成交金额**（我方从平台事件还原出来的），"
                  + "外加两者的**口径差异额**（平台侧 − 还原口径）。"
                  + "本维度**三者并列呈现**，不取其一冒充「销售额」 —— 只取一个等于抹掉口径差异。"
                  + "口径归属：9.1.1.1 平台单列表（trade）；口径差异额由 13.1.1.2 多店对比派生。",
              ""),
          new DimensionSpec(
              ORDER_COUNT,
              "订单量",
              List.of(ReportingStoreComparisonCatalog.STORE_ORDER_COUNT),
              "COUNT",
              false,
              Source.STORE_METRIC,
              "9.1.1.1 平台单列表（trade）",
              "",
              ""),
          new DimensionSpec(
              PROFIT_MARGIN,
              "利润率",
              List.of(),
              "PERCENT",
              true,
              Source.NOT_CONNECTED,
              "13.2 利润分析（未交付）/ 12.4.1.2 成本计算（finance）",
              "",
              "店铺维度的利润率**在店铺口径表里没有登记**：13.1.1.2 的 "
                  + "reporting_store_metric_definition 里没有任何利润 / 毛利类的**店铺级**指标码。"
                  + "上游的成本分录（12.4.1.2 成本计算）与利润表（12.5.2.2 财务报表）"
                  + "**只有法人 / 公司维度，没有店铺维度** ⇒ 把公司维度的利润率挂到店铺上就是造假。"
                  + "**不给公式也不给分母**（给了就是编一个出来）。"
                  + "归属模块：13.2 利润分析（未交付）/ 12 财务成本核算。"),
          new DimensionSpec(
              INVENTORY_DAYS,
              "库存天数",
              List.of(),
              "DAYS",
              false,
              Source.NOT_CONNECTED,
              "7.1.1.1 库存总览（warehouse）/ 13.5.1.1 库存分析（未交付）",
              "",
              "「库存天数」需要**店铺维度**的库存量与销量速度。上游在手库存（7.1.1.1 库存总览）"
                  + "按 **SKU × 法人 × 仓**组织，**没有店铺维度**；13.1.1.2 的店铺口径表里"
                  + "也没有库存天数指标码 ⇒ 店铺维度**不可得**。"
                  + "**不给公式也不给分母**（拿库存量 ÷ 销量速度就是在代码里新写一条口径公式 = 第二个真相）。"
                  + "归属模块：7.1.1.1 库存总览（warehouse）/ 13.5.1.1 库存分析。"),
          new DimensionSpec(
              AD_SPEND,
              "广告花费",
              List.of(ReportingStoreComparisonCatalog.STORE_AD_SPEND),
              "CURRENCY",
              true,
              Source.STORE_METRIC,
              "14.3.1.1 活动报表（channel）",
              "",
              ""));

  /** 按维度标识取声明（取不到返回 null —— 调用方据此说「该维度未登记」）。 */
  public static DimensionSpec dimension(String key) {
    for (var spec : DIMENSIONS) {
      if (spec.key().equals(key)) return spec;
    }
    return null;
  }
}
