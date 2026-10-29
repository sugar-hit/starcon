package com.starcon.reporting;

import java.util.List;

/**
 * 1.1.2.1 核心指标卡的**卡片清单**（系统事实：工作台一屏放哪几张卡）。
 *
 * <p>蓝图 01 卷 1.1.2.1 原文：「一屏展示今日/昨日/本月的销售额、订单量、销量、库存总额、
 * 毛利、回款、在途金额，每张卡带环比与同比，点击下钻到对应报表。」本类就是那句话的**逐条落地**。
 *
 * <p>⚠️ **这张清单是「系统事实」，不是「用户偏好」**（与 1.4.1.1 工作台布局同一条纪律）：
 * 卡片清单写在代码里，改一次对所有人生效；用户自己的排序与显隐是 1.4.1.1 的个人偏好。
 *
 * <p>🔴 **本类只声明「有哪些卡、每张卡的口径归谁、能不能接通」，不声明任何公式**：
 *
 * <ul>
 *   <li>口径（单位 / 聚合方式 / 分子分母）**运行时唯一真相是 {@code reporting_metric_definition}**
 *       —— 服务层从那张表读，本类不复制一份；</li>
 *   <li>派生型指标只写「分子 / 分母引用」，不写公式 SQL；</li>
 *   <li>接不通的指标（{@link Source#NOT_CONNECTED}）**不给公式也不给分母** ——
 *       给了就是编一个出来，页面上的数字会变成「看起来有依据的假数」。</li>
 * </ul>
 *
 * <p>🔴 **下钻目标只指向「已经存在的路由」**：蓝图说「点击下钻到对应报表」，
 * 但指向未交付的页面是**假承诺**（1.4.1.1 工作台布局的卡片清单同一条纪律）。
 * 所以每张卡要么指向一条已存在的页面路径，要么 {@code reportPath} 为空并写明原因。
 */
public final class ReportingMetricCardCatalog {

  private ReportingMetricCardCatalog() {}

  /** 卡片的取数来源。 */
  public enum Source {
    /** 走本服务的投影读模型（口径在 {@code reporting_metric_definition}）。 */
    REPORTING_PROJECTION,
    /** 走跨服务的**授权只读 API**（口径在归属系统，本服务只引用上游算好的合计）。 */
    AUTHORIZED_API,
    /** 口径尚未接通 —— 卡片按「未接通」显示，不给公式也不给分母。 */
    NOT_CONNECTED
  }

  /**
   * 一张卡的静态声明。
   *
   * @param key 卡片标识（稳定字符串：它会进工作台布局偏好，改名 = 所有人布局里那一张凭空消失）
   * @param metricCode 指标码（回款与在途金额的码是**本模块的卡片码**，不在口径表里 —— 口径归上游）
   * @param displayName 显示名
   * @param unit 单位（CURRENCY / COUNT / QUANTITY）
   * @param currencyAware 是否涉币
   * @param source 取数来源
   * @param ownerModule 口径归属模块
   * @param caliberHint 口径说明兜底（口径表里登记过的指标以**表里的说明**为准）
   * @param sourceTables 数据来源（表名 / 授权只读 API）
   * @param reportPath 已交付的报表页路径（null = 没有对应报表页，卡片不显示下钻入口）
   * @param reportName 报表页名称
   * @param unavailableReason 接不通的原因（{@code source == NOT_CONNECTED} 时必填）
   */
  public record CardSpec(
      String key,
      String metricCode,
      String displayName,
      String unit,
      boolean currencyAware,
      Source source,
      String ownerModule,
      String caliberHint,
      String sourceTables,
      String reportPath,
      String reportName,
      String unavailableReason) {}

  // ---------------------------------------------------------------------------
  // 卡片码常量
  // ---------------------------------------------------------------------------

  /** 回款：口径归 12 财务（收款单），**不在**本服务的口径表里。 */
  public static final String CASH_RECEIVED = "CASH_RECEIVED";

  /** 在途金额：口径尚未接通（上游只给数量，不给金额）。 */
  public static final String IN_TRANSIT_VALUE = "IN_TRANSIT_VALUE";

  /**
   * 卡片清单（顺序 = 卡面顺序）。
   *
   * <p>⚠️ 顺序按「经营先看什么」排：销售额 → 订单量 → 销量 → 毛利 → 库存总额 → 回款 → 在途金额。
   */
  public static final List<CardSpec> CARDS =
      List.of(
          new CardSpec(
              "metric.sales-amount",
              ReportingMetricCatalog.SALES_AMOUNT,
              "销售额",
              "CURRENCY",
              true,
              Source.REPORTING_PROJECTION,
              "9.1.1.1 订单列表（trade）",
              "",
              "reporting_metric_fact（口径：reporting_metric_definition）",
              "/analytics",
              "13.1.1.1 经营总览",
              ""),
          new CardSpec(
              "metric.order-count",
              ReportingMetricCatalog.ORDER_COUNT,
              "订单量",
              "COUNT",
              false,
              Source.REPORTING_PROJECTION,
              "9.1.1.1 订单列表（trade）",
              "",
              "reporting_metric_fact（口径：reporting_metric_definition）",
              "/analytics",
              "13.1.1.1 经营总览",
              ""),
          new CardSpec(
              "metric.unit-sold",
              ReportingMetricCatalog.UNIT_SOLD,
              "销量",
              "QUANTITY",
              false,
              Source.REPORTING_PROJECTION,
              "9.1.1.1 订单列表（trade）",
              "",
              "reporting_metric_fact（口径：reporting_metric_definition）",
              "/analytics",
              "13.1.1.1 经营总览",
              ""),
          new CardSpec(
              "metric.gross-profit",
              ReportingMetricCatalog.GROSS_PROFIT,
              "毛利",
              "CURRENCY",
              true,
              Source.REPORTING_PROJECTION,
              "13.1.1.1 经营总览（本服务派生）",
              "",
              "reporting_metric_fact（口径：reporting_metric_definition，派生型只写分子分母引用）",
              "/analytics",
              "13.1.1.1 经营总览",
              ""),
          new CardSpec(
              "metric.inventory-value",
              ReportingMetricCatalog.INVENTORY_VALUE,
              "库存总额",
              "CURRENCY",
              true,
              Source.REPORTING_PROJECTION,
              "7.1.1.1 库存总览（warehouse）× 5.2.1.1 成本构成（catalog）",
              "",
              "reporting_metric_fact（口径：reporting_metric_definition）",
              "/analytics",
              "13.1.1.1 经营总览",
              ""),
          new CardSpec(
              "metric.cash-received",
              CASH_RECEIVED,
              "回款",
              "CURRENCY",
              true,
              Source.AUTHORIZED_API,
              "12.2.1.1 收款单（finance）",
              "回款 = 到账收款单的金额合计（按币种分别统计，不跨币种相加）；作废单不计入。"
                  + "口径归 12.2.1.1 收款单（finance），本模块**只引用上游算好的合计**，不重算。",
              "GET /finance/receipts（12.2.1.1 收款单，授权只读 API）",
              "/finance/receipts",
              "12.2.1.1 收款单",
              ""),
          new CardSpec(
              "metric.in-transit-value",
              IN_TRANSIT_VALUE,
              "在途金额",
              "CURRENCY",
              true,
              Source.NOT_CONNECTED,
              "06 采购在途（trade）/ 07 库存在途（warehouse）",
              "",
              "",
              null,
              null,
              "在途金额的口径尚未接通：① 本服务的口径表里没有这个指标码"
                  + "（口径表是 13 的单点定义，不在本表里的指标一律不接）；"
                  + "② 上游在途事实只给**数量**（在途腿数、在途件数），不给金额 —— "
                  + "把数量乘以某个成本价就是**在代码里新写一条指标公式**，"
                  + "那会出现第二个真相（本仓明令禁止重算口径）。"
                  + "归属模块：06 采购在途 / 07 库存在途。"));
}
