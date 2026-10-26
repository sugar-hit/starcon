package com.starcon.reporting;

import java.util.List;

/**
 * 13.1.1.1 经营总览的**标准指标口径**（口径唯一的「标准份」）。
 *
 * <p>⚠️ **口径唯一**：一个指标只允许一处定义，其他地方引用不允许重算。
 * 本类是**标准口径的默认值**（第一次投影时按租户播种一份到
 * {@code reporting_metric_definition}），而**运行时唯一的真相是那张表** ——
 * 租户改了口径，查询就跟着改；查询 SQL 里**不允许**出现任何硬编码的指标公式。
 *
 * <p><b>派生型指标只写「分子 / 分母引用」，不写公式</b>：毛利率写成
 * {@code GROSS_PROFIT ÷ SALES_AMOUNT}，而不是「(sales − cost) ÷ sales」。
 * 前者改了成本口径毛利率自动跟着改，后者会出现两个真相。
 *
 * <p>⚠️ **播种顺序有意义**：派生指标的分子分母必须**先存在**（DB 触发器
 * {@code guard_reporting_metric_definition_ref} 会拒绝口径不成立的定义），
 * 所以列表里派生指标永远排在它依赖的指标之后。
 */
public final class ReportingMetricCatalog {

  private ReportingMetricCatalog() {}

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
    /** 存量型：取窗口内**最近一天**的快照值（余额不能累加）。 */
    LAST,
    /** 派生差：分子 − 分母。 */
    DERIVED_DIFF,
    /** 派生比：分子 ÷ 分母（分母为 0 ⇒ 算不出，不是 0）。 */
    DERIVED_RATIO
  }

  /** 一个指标的口径定义。 */
  public record Definition(
      String metricCode,
      String displayName,
      Unit unit,
      Aggregation aggregation,
      String numeratorMetricCode,
      String denominatorMetricCode,
      boolean currencyAware,
      String description,
      String ownerModule) {}

  // ---------------------------------------------------------------------------
  // 10 个标准指标（蓝图 13.1.1.1 点名的那些）+ 3 个派生口径
  // ---------------------------------------------------------------------------

  /** 销售额：订单成交金额。 */
  public static final String SALES_AMOUNT = "SALES_AMOUNT";
  /** 订单量：成交订单笔数。 */
  public static final String ORDER_COUNT = "ORDER_COUNT";
  /** 销量：成交件数。 */
  public static final String UNIT_SOLD = "UNIT_SOLD";
  /** 成本：出库成本。 */
  public static final String COGS_AMOUNT = "COGS_AMOUNT";
  /** 毛利额 = 销售额 − 成本。 */
  public static final String GROSS_PROFIT = "GROSS_PROFIT";
  /** 毛利率 = 毛利额 ÷ 销售额。 */
  public static final String GROSS_MARGIN = "GROSS_MARGIN";
  /** 广告花费。 */
  public static final String AD_SPEND = "AD_SPEND";
  /** 净利（12 财务口径）。 */
  public static final String NET_PROFIT = "NET_PROFIT";
  /** 退货额。 */
  public static final String RETURNED_AMOUNT = "RETURNED_AMOUNT";
  /** 退货率 = 退货额 ÷ 销售额。 */
  public static final String RETURN_RATE = "RETURN_RATE";
  /** 库存总额（成本法）。 */
  public static final String INVENTORY_VALUE = "INVENTORY_VALUE";
  /** 应收余额。 */
  public static final String RECEIVABLE_BALANCE = "RECEIVABLE_BALANCE";

  /**
   * 标准口径清单。
   *
   * <p>⚠️ 顺序即播种顺序：派生指标必须排在其依赖之后（DB 触发器会校验）。
   */
  public static final List<Definition> STANDARD =
      List.of(
          new Definition(
              SALES_AMOUNT,
              "销售额",
              Unit.CURRENCY,
              Aggregation.SUM,
              null,
              null,
              true,
              "成交订单的含税成交金额，按订单币种分别统计，不跨币种相加。口径归属：9.1.1.1 订单列表（trade）。",
              "9.1.1.1"),
          new Definition(
              ORDER_COUNT,
              "订单量",
              Unit.COUNT,
              Aggregation.SUM,
              null,
              null,
              false,
              "成交订单笔数（按订单去重）。口径归属：9.1.1.1 订单列表（trade）。",
              "9.1.1.1"),
          new Definition(
              UNIT_SOLD,
              "销量",
              Unit.QUANTITY,
              Aggregation.SUM,
              null,
              null,
              false,
              "成交订单行的数量合计。口径归属：9.1.1.1 订单列表（trade）。",
              "9.1.1.1"),
          new Definition(
              COGS_AMOUNT,
              "成本",
              Unit.CURRENCY,
              Aggregation.SUM,
              null,
              null,
              true,
              "出库成本（成本法），由 12 财务的成本计算口径给出。口径归属：12.4.1.2 成本计算（finance）。",
              "12.4.1.2"),
          new Definition(
              AD_SPEND,
              "广告花费",
              Unit.CURRENCY,
              Aggregation.SUM,
              null,
              null,
              true,
              "平台广告活动花费合计，按币种分别统计。口径归属：14.3.1.1 活动报表（channel）。",
              "14.3.1.1"),
          new Definition(
              RETURNED_AMOUNT,
              "退货额",
              Unit.CURRENCY,
              Aggregation.SUM,
              null,
              null,
              true,
              "售后退货的申请金额合计（按币种分别统计）。口径归属：11.1.1.1 售后单列表（trade）。",
              "11.1.1.1"),
          new Definition(
              NET_PROFIT,
              "净利",
              Unit.CURRENCY,
              Aggregation.SUM,
              null,
              null,
              true,
              "净利润，取 12 财务利润表口径（读利润表的「本期损益」行，本服务不自己加总收入与成本）。口径归属：12.5.2.2 财务报表（finance）。",
              "12.5.2.2"),
          new Definition(
              INVENTORY_VALUE,
              "库存总额",
              Unit.CURRENCY,
              Aggregation.LAST,
              null,
              null,
              true,
              "在手库存按成本法估值后的金额，取窗口内最近一天的快照。口径归属：7.1.1.1 库存总览（warehouse）× 5.2.1.1 成本构成（catalog）。",
              "7.1.1.1"),
          new Definition(
              RECEIVABLE_BALANCE,
              "应收余额",
              Unit.CURRENCY,
              Aggregation.LAST,
              null,
              null,
              true,
              "未核销应收余额，取窗口内最近一天的快照（余额不能累加）。口径归属：12.1.1.1 应收单管理（finance）。",
              "12.1.1.1"),
          // ---- 派生口径（依赖上面已登记的指标）----
          new Definition(
              GROSS_PROFIT,
              "毛利额",
              Unit.CURRENCY,
              Aggregation.DERIVED_DIFF,
              SALES_AMOUNT,
              COGS_AMOUNT,
              true,
              "毛利额 = 销售额 − 成本。分子分母任一缺失时不算（不用 0 冒充）。口径归属：13.1.1.1 经营总览（本服务派生）。",
              "13.1.1.1 经营总览"),
          new Definition(
              GROSS_MARGIN,
              "毛利率",
              Unit.PERCENT,
              Aggregation.DERIVED_RATIO,
              GROSS_PROFIT,
              SALES_AMOUNT,
              true,
              "毛利率 = 毛利额 ÷ 销售额。分母为 0 时算不出（不是 0%）。口径归属：13.1.1.1 经营总览（本服务派生）。",
              "13.1.1.1 经营总览"),
          new Definition(
              RETURN_RATE,
              "退货率",
              Unit.PERCENT,
              Aggregation.DERIVED_RATIO,
              RETURNED_AMOUNT,
              SALES_AMOUNT,
              true,
              "退货率 = 退货额 ÷ 销售额。分母为 0 时算不出（不是 0%）。口径归属：13.1.1.1 经营总览（本服务派生）。",
              "13.1.1.1 经营总览"));

  /** 按指标码取标准定义（取不到返回 null —— 调用方据此说「该指标未登记」）。 */
  public static Definition standard(String metricCode) {
    for (var definition : STANDARD) {
      if (definition.metricCode().equals(metricCode)) return definition;
    }
    return null;
  }
}
