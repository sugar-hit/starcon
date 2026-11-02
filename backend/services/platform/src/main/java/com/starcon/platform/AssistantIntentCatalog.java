package com.starcon.platform;

import com.starcon.platform.AssistantModels.AssistantNextAction;
import java.util.List;
import java.util.Locale;

/**
 * 18.4.1.1「对话助手」的**受控意图目录**。
 *
 * <p>蓝图加粗句：「**答案必须附明细链接**——不给依据的 AI 数字不能用于决策」。
 * 与它配套的另一条纪律是：**不把任意自然语言直译成任意 SQL**（那是 18.4.1.2「智能查询」的边界）。
 *
 * <p>⇒ 本模块只认**封闭词表**里的意图，每个意图对应**一条写死的只读查询**：
 * 「上周哪个店利润最差」永远走「多模式利润报表（店铺维度）」那一条查询，
 * 而不是让模型临时拼一条 SQL 出来。
 *
 * <p><b>意图识别是确定性的关键词匹配</b>（不是模型自由理解）：
 * 强关键词权重 3、弱关键词权重 1，得分最高者胜出，同分时按目录顺序（更具体的排前面）。
 * 一个都没命中 ⇒ 走自由问句路径 ⇒ {@link AiTextGenerationPort} 如实答「未接通」。
 *
 * <p><b>口径说明是目录的一部分</b>：每个意图都必须说清「数字从哪个上游的哪个端点来、
 * 算什么、哪些格子算不出来」—— 答案里要带它。
 *
 * <p>⚠️ <b>目录里没有的意图就不存在</b>：本类**刻意只收录 7 条**（≤ 8 的上限），
 * 每一条都有真实的只读数据源。宁可少收一条，也不要给一个「看起来能问、其实查不出来」的意图。
 */
final class AssistantIntentCatalog {

  private AssistantIntentCatalog() {}

  /**
   * 一个受控意图的定义。
   *
   * @param strongKeywords 强关键词（权重 3）：命中即基本可判定意图
   * @param weakKeywords 弱关键词（权重 1）：只在与强关键词同时出现时才起作用
   */
  record Definition(
      String code,
      String name,
      List<String> examples,
      List<String> strongKeywords,
      List<String> weakKeywords,
      String sourceService,
      String sourceEndpoint,
      String caliber,
      String detailRoute,
      List<AssistantNextAction> nextActions) {}

  // ================================================================ 目录（7 条）

  static final List<Definition> DEFINITIONS =
      List.of(
          new Definition(
              "SHOP_PROFIT_RANKING",
              "店铺利润排名",
              List.of("上周哪个店利润最差", "哪几个店毛利最低", "店铺利润排名"),
              List.of("利润", "毛利", "不赚钱", "亏"),
              List.of("店铺", "店", "哪个店", "分店"),
              "reporting",
              "/reporting/profit-report/report?dimension=SHOP",
              "多模式利润报表（13.4.1.1）店铺维度：毛利额 = 收入 − 各成本项之和，"
                  + "按币种分别计算，**不跨币种比较**（不同币种的毛利额不是同一个量纲）。"
                  + "缺成本项的行在「不可比」里列出来，不会当成 0。",
              "/analytics/profit/profit-report",
              List.of(
                  new AssistantNextAction("打开利润报表", "/analytics/profit/profit-report", "13.4.1.1"),
                  new AssistantNextAction(
                      "打开多店对比", "/analytics/dashboard/store-comparison", "13.1.1.2"))),

          new Definition(
              "INVENTORY_REORDER",
              "补货与库存周转",
              List.of("哪些 SKU 该补货了", "库存周转怎么样", "哪些商品缺货了"),
              List.of("补货", "缺货", "要进货", "该进了", "进多少"),
              List.of("库存", "周转", "呆滞", "库龄", "断货"),
              "reporting",
              "/reporting/inventory-analysis/drilldown",
              "库存分析（13.5.1.1）SKU 级下钻：在手量 / 可用量 / 窗口销量 / 缺货标记，"
                  + "库存金额按**成本口径**（单位成本来自商品成本构成）。"
                  + "判定「该补货」的口径：可用量 ≤ 0 记为缺货；可用量低于窗口销量（按窗口日均销量推算的覆盖天数不足）记为需补货。"
                  + "**没有维护成本的 SKU 不进金额口径**，它的金额是「算不出来」而不是 0。",
              "/analytics/inventory/analysis",
              List.of(
                  new AssistantNextAction("打开库存分析", "/analytics/inventory/analysis", "13.5.1.1"),
                  new AssistantNextAction("打开缺货预警", "/inventory/stockout-alerts", "7.8.2.2"))),

          new Definition(
              "PRODUCT_TOP_SELLER",
              "商品销量表现",
              List.of("哪个 SKU 卖得最好", "哪些商品卖得最好", "商品表现怎么样"),
              List.of("卖得最好", "卖得最差", "销量最高", "最好卖", "卖得最多"),
              List.of("卖", "销量", "sku", "商品表现", "出货"),
              "reporting",
              "/reporting/sales-trend?metricCodes=PRODUCT_SALES_QUANTITY",
              "商品维度销量（13.2）= 销售订单明细行的数量之和，按 SKU 码分组，"
                  + "只统计**取到明细**的订单（明细有上限，刷新结果里会报出截断）。"
                  + "销量是**件数**口径，与金额口径不是同一个数（订单级折扣 / 运费 / 税不摊到行），不能相加。",
              "/analytics/sales/products",
              List.of(
                  new AssistantNextAction("打开商品销量", "/analytics/sales/products", "13.2"),
                  new AssistantNextAction("打开商品表现", "/analytics/products/performance", "13.5"))),

          new Definition(
              "FUND_CASHFLOW",
              "资金与现金流",
              List.of("本月现金流怎么样", "资金状况如何", "回款情况怎么样"),
              List.of("现金流", "资金", "回款"),
              List.of("余额", "收款", "付款", "账户"),
              "finance",
              "/finance/fund-analysis/cash-flow-trend",
              "资金分析（12.7.2.1）现金流入流出趋势：三个口径并列（实际流入 / 预期流入 / 计划流出），"
                  + "响应里逐项标注、不混。**实际流出没有时间序列**（上游只给了这一个口径的说明），"
                  + "净流入因此是「实际流入 − 计划流出」这一口径的净值，不是完整现金流。",
              "/finance/fund-analysis",
              List.of(
                  new AssistantNextAction("打开资金分析", "/finance/fund-analysis", "12.7.2.1"),
                  new AssistantNextAction("打开收款单", "/finance/receipts", "12.1.1.1"))),

          new Definition(
              "LIFECYCLE_DECLINE",
              "走下坡商品名单",
              List.of("最近有哪些商品在走下坡", "哪些商品销量在掉", "哪些商品该清仓了"),
              List.of("走下坡", "下滑", "衰退", "掉量", "掉得快"),
              List.of("生命周期", "分层", "清仓", "动销"),
              "reporting",
              "/reporting/lifecycle-segmentation",
              "生命周期与分层（13.3.1.2）商品维度：按「近期销量 vs 前期销量」的下行幅度与生命周期档位判定。"
                  + "⚠️ **这是商品（SKU）维度的下行名单，不是客户维度的流失名单**（客户流失见「高价值客户流失名单」）。"
                  + "档位算不出来时是「不可比」并附原因，不会落到某个默认档位。",
              "/analytics/products/lifecycle",
              List.of(
                  new AssistantNextAction("打开生命周期分层", "/analytics/products/lifecycle", "13.3.1.2"))),

          new Definition(
              "CUSTOMER_CHURN",
              "高价值客户流失名单",
              List.of("最近有哪些高价值客户在流失", "哪些客户要流失了", "流失预警有哪些"),
              List.of("流失", "流失预警", "不来了", "流失风险"),
              List.of("客户", "复购", "预警", "回购"),
              "crm",
              "/crm/customer-lifecycle/alerts",
              "生命周期与流失预警（15.6.2.1）：按「距上次下单天数 − 预期复购天数 − 容差天数」判超出期天数，"
                  + "风险分 0–100 来自上游规则（阈值归 crm，平台不自己定阈值）。"
                  + "**风险分不是模型置信度**，是规则分；未处理 / 已确认 / 已解决三种状态分别列出。",
              "/crm/customer-lifecycle",
              List.of(
                  new AssistantNextAction("打开客户生命周期", "/crm/customer-lifecycle", "15.6.2.1"))),

          new Definition(
              "ORDER_PENDING_SHIPMENT",
              "待发货积压",
              List.of("哪些订单卡在待发货", "待发货积压多少", "有没有发货超时的订单"),
              List.of("待发货", "卡在", "积压", "压着"),
              List.of("发货", "订单", "超时", "包裹", "揽收"),
              "trade",
              "/trade/fulfillment/pending-shipment/units",
              "待发货（9.4.1.1）发货单元清单：一行 = 一个待发货包裹单元，"
                  + "含仓库 / 订单号 / 承运商 / SLA 截止时刻。**SLA 档位由上游判**（平台不自己算超时），"
                  + "打包时刻与 SLA 截止时刻都在行里，可以逐条核对。",
              "/orders/fulfillment/pending-shipment",
              List.of(
                  new AssistantNextAction(
                      "打开待发货看板", "/orders/fulfillment/pending-shipment", "9.4.1.1"),
                  new AssistantNextAction("打开履约时效", "/orders/fulfillment/timeliness", "9.4.1.2"))));

  // ================================================================ 时间范围

  /** 时间范围代号。 */
  static final String LAST_7_DAYS = "LAST_7_DAYS";
  static final String LAST_30_DAYS = "LAST_30_DAYS";
  static final String LAST_WEEK = "LAST_WEEK";
  static final String THIS_MONTH = "THIS_MONTH";

  /** 默认时间范围（受控目录里没提时间时的兜底）。 */
  static final String DEFAULT_PERIOD = LAST_30_DAYS;

  /**
   * 从问句里认出时间范围。
   *
   * @return 认出的代号；**没提时间时返回 {@code null}**（⇒ 沿用会话上下文里的口径，
   *     这正是「换成上周」这类追问只替换被显式修改的维度的实现）
   */
  static String periodOf(String question) {
    if (question == null) return null;
    var q = question.toLowerCase(Locale.ROOT);
    if (q.contains("上周") || q.contains("上星期") || q.contains("上个星期") || q.contains("上一周")) {
      return LAST_WEEK;
    }
    if (q.contains("本月") || q.contains("这个月") || q.contains("当月")) {
      return THIS_MONTH;
    }
    if (q.contains("最近7天")
        || q.contains("最近 7 天")
        || q.contains("近7天")
        || q.contains("近 7 天")
        || q.contains("最近一周")) {
      return LAST_7_DAYS;
    }
    if (q.contains("最近30天")
        || q.contains("最近 30 天")
        || q.contains("近30天")
        || q.contains("近 30 天")
        || q.contains("最近一个月")
        || q.contains("近一个月")
        || q.contains("最近")) {
      return LAST_30_DAYS;
    }
    return null;
  }

  // ================================================================ 追问：名次

  /**
   * 从问句里认出「第几名」。
   *
   * <p>「那第二名呢」「那第 3 名呢」「前三名」都能认出来。
   *
   * @return 名次（1 基）；没提名次时返回 {@code null}
   */
  static Integer rankOf(String question) {
    if (question == null) return null;
    var q = question.replace(" ", "");
    var digits = "一二三四五六七八九十";
    // 「第N名 / 第N / 第N个」
    for (int i = 0; i < digits.length(); i++) {
      var cn = String.valueOf(digits.charAt(i));
      if (q.contains("第" + cn + "名") || q.contains("第" + cn)) {
        var parsed = chineseNumber(cn);
        if (parsed != null) return parsed;
      }
    }
    // 阿拉伯数字：第3名 / 第3
    var matcher = java.util.regex.Pattern.compile("第(\\d+)");
    var m = matcher.matcher(q);
    if (m.find()) {
      try {
        var n = Integer.parseInt(m.group(1));
        if (n > 0) return n;
      } catch (NumberFormatException ignored) {
        // 数字太大就不当名次处理。
      }
    }
    // 「前N名」
    var prefix = java.util.regex.Pattern.compile("前(\\d+)");
    var p = prefix.matcher(q);
    if (p.find()) {
      try {
        var n = Integer.parseInt(p.group(1));
        if (n > 0) return n;
      } catch (NumberFormatException ignored) {
        // 同上。
      }
    }
    return null;
  }

  /** 「十」= 10；「十一」这类组合词不在本模块的追问范围里（追问最多问前几名）。 */
  private static Integer chineseNumber(String cn) {
    var digits = "一二三四五六七八九";
    var index = digits.indexOf(cn);
    if (index < 0) return null;
    return index + 1;
  }

  /**
   * 认出「这是不是一句追问」。
   *
   * <p>追问的判据是**它自己不带意图关键词**（否则就是换了意图的新问题），
   * 而且句子里有追问语气或换维度的话。
   */
  static boolean isFollowUp(String question) {
    if (question == null || question.isBlank()) return false;
    var q = question.replace(" ", "");
    return q.contains("那")
        || q.contains("呢")
        || q.contains("换成")
        || q.contains("改成")
        || q.contains("还有呢")
        || q.contains("如果")
        || q.contains("再问")
        || q.contains("换成别的");
  }

  // ================================================================ 匹配

  /** 按意图码取定义（找不到返回 {@code null}）。 */
  static Definition byCode(String code) {
    if (code == null || code.isBlank()) return null;
    for (var d : DEFINITIONS) {
      if (d.code().equals(code.trim().toUpperCase(Locale.ROOT))) return d;
    }
    return null;
  }

  /**
   * 按关键词匹配意图（**确定性**，不是模型理解）。
   *
   * @return 命中的意图；**一个都没命中返回 {@code null}** ⇒ 调用方走自由问句路径
   */
  static Definition match(String question) {
    if (question == null || question.isBlank()) return null;
    var q = question.toLowerCase(Locale.ROOT);
    Definition best = null;
    var bestScore = 0;
    for (var d : DEFINITIONS) {
      var score = 0;
      for (var kw : d.strongKeywords()) {
        if (q.contains(kw.toLowerCase(Locale.ROOT))) score += 3;
      }
      for (var kw : d.weakKeywords()) {
        if (q.contains(kw.toLowerCase(Locale.ROOT))) score += 1;
      }
      // ⚠️ 弱关键词**单独**命中不算数：只命中一个「店」就判成店铺利润意图是错的。
      if (score > 0 && (score > bestScore || (score == bestScore && best == null))) {
        best = d;
        bestScore = score;
      }
    }
    return bestScore > 0 ? best : null;
  }
}
