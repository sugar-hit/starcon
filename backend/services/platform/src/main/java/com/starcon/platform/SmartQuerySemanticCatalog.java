package com.starcon.platform;

import java.util.List;
import java.util.Locale;

/**
 * 18.4.1.2「智能查询」的**受控语义层词表**（封闭词表）。
 *
 * <p>蓝图加粗句：「<b>自然语言只映射到受控语义层指标，不生成任意 SQL</b>」。
 *
 * <p>⇒ 这个类就是那句话的**物理边界**：
 *
 * <ol>
 *   <li>能出现在查询条件里的**指标**只有 {@link #METRICS} 里的那些 ——
 *       每一个都对应 13.1.1.1 经营总览的口径表（{@code reporting_metric_definition}）
 *       里**已登记**的 {@code metric_code}，口径说明与归属模块**逐字沿用**，
 *       本模块**不重写一份**；
 *   <li>能出现的**维度**只有 {@link #DIMENSIONS} 里的 5 个 ——
 *       与 13.6.1.1 自定义报表的白名单**逐字一致**；
 *   <li>筛选运算符只有 EQ / IN / GTE / LTE / BETWEEN（13.6.1.1 的同一套）；
 *   <li>解析结果里的每一个值都来自**本类的词表**，
 *       <b>用户输入永远不进 SQL 文本</b> —— 它只作为绑定参数传给下游的预编译查询。
 * </ol>
 *
 * <p><b>🔴 词表里没有的东西，就是不存在。</b> 例如「库存周转率」不在词表里
 * （13.1.1.1 的口径表没有这个指标），问它就如实答「不支持这个问法 + 缺什么 + 归谁」，
 * <b>不猜、不拿别的指标顶替、不返回任何数字</b>。
 *
 * <p><b>识别是确定性的</b>（不是模型自由理解）：强关键词权重 3、弱关键词权重 1，
 * <b>只命中弱词不算数</b>（只命中一个「店」就判成店铺维度是错的）；
 * 同分时按「命中的最长关键词长度」再按目录顺序裁决 —— 两条规则都是确定的，
 * 同样的问句永远得到同样的结果。
 */
final class SmartQuerySemanticCatalog {

  private SmartQuerySemanticCatalog() {}

  /**
   * 一个**受控指标**。
   *
   * @param strongKeywords 强关键词（权重 3）：命中即基本可判定
   * @param weakKeywords 弱关键词（权重 1）：只做加权，<b>单独命中不算数</b>
   */
  record MetricDefinition(
      String code,
      String name,
      String unit,
      String aggregation,
      boolean currencyAware,
      List<String> strongKeywords,
      List<String> weakKeywords,
      String sourceService,
      String sourceEndpoint,
      String caliber,
      String detailRoute,
      String ownerModule) {}

  /**
   * 一个**受控维度**（与 13.6.1.1 的白名单逐字一致）。
   *
   * @param valueKind TEXT / UUID / DATE
   */
  record DimensionDefinition(
      String code,
      String name,
      String valueKind,
      List<String> allowedOperators,
      List<String> strongKeywords,
      List<String> weakKeywords,
      String ownerModule) {}

  // ==========================================================================
  // 指标词表（12 条 —— 与 13.1.1.1 的标准口径**逐字对应**）
  // ==========================================================================

  /** 口径查询引擎的端点（数字真的从这里出来）。 */
  static final String REPORT_RUN_ENDPOINT = "/reporting/custom-report/reports/{reportId}/run";

  /** 口径目录的端点（这个指标存不存在、口径是什么）。 */
  static final String REPORT_METRICS_ENDPOINT = "/reporting/custom-report/metrics";

  /** 明细下钻页（前端**真实存在**的路由）。 */
  static final String DETAIL_ROUTE = "/analytics/custom-report";

  static final List<MetricDefinition> METRICS =
      List.of(
          new MetricDefinition(
              "SALES_AMOUNT",
              "销售额",
              "CURRENCY",
              "SUM",
              true,
              List.of("销售额", "营业额", "销售收入", "总营收"),
              List.of("销售", "营收", "业绩"),
              "reporting",
              REPORT_RUN_ENDPOINT,
              "成交订单的含税成交金额，按订单币种分别统计，不跨币种相加。口径归属：9.1.1.1 订单列表（trade）。"
                  + "指标目录：" + REPORT_METRICS_ENDPOINT + "；取数走 13.6.1.1 的查询引擎，"
                  + "事实住在 reporting_metric_fact。",
              DETAIL_ROUTE,
              "9.1.1.1"),

          new MetricDefinition(
              "ORDER_COUNT",
              "订单量",
              "COUNT",
              "SUM",
              false,
              List.of("订单量", "订单数", "单量", "成交笔数"),
              List.of("订单", "单"),
              "reporting",
              REPORT_RUN_ENDPOINT,
              "成交订单笔数（按订单去重）。口径归属：9.1.1.1 订单列表（trade）。"
                  + "与货币无关 ⇒ 报表给一个**跨币种合计**（币种显示为空串，不是「币种未知」）。",
              DETAIL_ROUTE,
              "9.1.1.1"),

          new MetricDefinition(
              "UNIT_SOLD",
              "销量",
              "QUANTITY",
              "SUM",
              false,
              List.of("销量", "销售量", "出货量", "件数", "卖了多少件"),
              List.of("卖", "出货"),
              "reporting",
              REPORT_RUN_ENDPOINT,
              "成交订单行的数量合计。口径归属：9.1.1.1 订单列表（trade）。"
                  + "销量是**件数**口径，与金额口径不是同一个数（订单级折扣 / 运费 / 税不摊到行），不能相加。",
              DETAIL_ROUTE,
              "9.1.1.1"),

          new MetricDefinition(
              "COGS_AMOUNT",
              "成本",
              "CURRENCY",
              "SUM",
              true,
              List.of("成本", "出库成本", "销货成本"),
              List.of("进货成本"),
              "reporting",
              REPORT_RUN_ENDPOINT,
              "出库成本（成本法），由 12 财务的成本计算口径给出。口径归属：12.4.1.2 成本计算（finance）。"
                  + "按币种分别统计，不跨币种相加。",
              DETAIL_ROUTE,
              "12.4.1.2"),

          new MetricDefinition(
              "GROSS_PROFIT",
              "毛利额",
              "CURRENCY",
              "DERIVED_DIFF",
              true,
              List.of("毛利额", "毛利", "毛利润"),
              List.of("利润", "赚"),
              "reporting",
              REPORT_RUN_ENDPOINT,
              "毛利额 = 销售额 − 成本（派生型：分子 SALES_AMOUNT、分母 COGS_AMOUNT，"
                  + "服务端**分别取数再算**，不自己写公式）。口径归属：13.1.1.1 经营总览（本服务派生）。"
                  + "🔴 分子分母任一缺失时**不算**（不是用 0 冒充）；按币种分别统计，不跨币种相加。",
              DETAIL_ROUTE,
              "13.1.1.1 经营总览"),

          new MetricDefinition(
              "GROSS_MARGIN",
              "毛利率",
              "PERCENT",
              "DERIVED_RATIO",
              true,
              List.of("毛利率", "毛利比例"),
              List.of("利润率"),
              "reporting",
              REPORT_RUN_ENDPOINT,
              "毛利率 = 毛利额 ÷ 销售额（派生型：分子 GROSS_PROFIT、分母 SALES_AMOUNT）。"
                  + "口径归属：13.1.1.1 经营总览（本服务派生）。"
                  + "🔴 **分母为 0 ⇒ 算不出来（不是 0%）** —— 「压根算不出来」与「算出来是零」是两件事。",
              DETAIL_ROUTE,
              "13.1.1.1 经营总览"),

          new MetricDefinition(
              "AD_SPEND",
              "广告花费",
              "CURRENCY",
              "SUM",
              true,
              List.of("广告花费", "广告费", "广告支出", "广告成本"),
              List.of("广告", "投放"),
              "reporting",
              REPORT_RUN_ENDPOINT,
              "平台广告活动花费合计，按币种分别统计。口径归属：14.3.1.1 活动报表（channel）。",
              DETAIL_ROUTE,
              "14.3.1.1"),

          new MetricDefinition(
              "RETURNED_AMOUNT",
              "退货额",
              "CURRENCY",
              "SUM",
              true,
              List.of("退货额", "退货金额", "退款金额", "退款额"),
              List.of("退货", "退款"),
              "reporting",
              REPORT_RUN_ENDPOINT,
              "售后退货的申请金额合计（按币种分别统计）。口径归属：11.1.1.1 售后单列表（trade）。",
              DETAIL_ROUTE,
              "11.1.1.1"),

          new MetricDefinition(
              "RETURN_RATE",
              "退货率",
              "PERCENT",
              "DERIVED_RATIO",
              true,
              List.of("退货率", "退货比例"),
              List.of("退的比例"),
              "reporting",
              REPORT_RUN_ENDPOINT,
              "退货率 = 退货额 ÷ 销售额（派生型：分子 RETURNED_AMOUNT、分母 SALES_AMOUNT）。"
                  + "口径归属：13.1.1.1 经营总览（本服务派生）。"
                  + "🔴 **分母为 0 ⇒ 算不出来（不是 0%）**。",
              DETAIL_ROUTE,
              "13.1.1.1 经营总览"),

          new MetricDefinition(
              "NET_PROFIT",
              "净利",
              "CURRENCY",
              "SUM",
              true,
              List.of("净利润", "净利", "净利额"),
              List.of("利润"),
              "reporting",
              REPORT_RUN_ENDPOINT,
              "净利润，取 12 财务利润表口径（读利润表的「本期损益」行，本服务不自己加总收入与成本）。"
                  + "口径归属：12.5.2.2 财务报表（finance）。按币种分别统计，不跨币种相加。",
              DETAIL_ROUTE,
              "12.5.2.2"),

          new MetricDefinition(
              "INVENTORY_VALUE",
              "库存总额",
              "CURRENCY",
              "LAST",
              true,
              List.of("库存总额", "库存金额", "存货价值", "库存价值"),
              List.of("库存", "存货"),
              "reporting",
              REPORT_RUN_ENDPOINT,
              "在手库存按成本法估值后的金额，取**窗口末之前最近一天的快照**（余额不能累加）。"
                  + "口径归属：7.1.1.1 库存总览（warehouse）× 5.2.1.1 成本构成（catalog）。"
                  + "🔴 **存量型指标不能求和**：问「库存合计」本模块如实答不支持。",
              DETAIL_ROUTE,
              "7.1.1.1"),

          new MetricDefinition(
              "RECEIVABLE_BALANCE",
              "应收余额",
              "CURRENCY",
              "LAST",
              true,
              List.of("应收余额", "应收账款", "应收款"),
              List.of("应收", "欠款"),
              "reporting",
              REPORT_RUN_ENDPOINT,
              "未核销应收余额，取**窗口末之前最近一天的快照**（余额不能累加）。"
                  + "口径归属：12.1.1.1 应收单管理（finance）。",
              DETAIL_ROUTE,
              "12.1.1.1"));

  // ==========================================================================
  // 维度词表（5 条 —— 与 13.6.1.1 的白名单**逐字一致**）
  // ==========================================================================

  static final List<DimensionDefinition> DIMENSIONS =
      List.of(
          new DimensionDefinition(
              "SCOPE_TYPE",
              "公司 / 法人 / 店铺",
              "TEXT",
              List.of("EQ", "IN"),
              List.of("数据范围", "层级"),
              List.of("范围"),
              ""),
          new DimensionDefinition(
              "LEGAL_ENTITY",
              "法人（uuid）",
              "UUID",
              List.of("EQ", "IN"),
              List.of("法人"),
              List.of("主体"),
              ""),
          new DimensionDefinition(
              "SHOP",
              "店铺（uuid）",
              "UUID",
              List.of("EQ", "IN"),
              List.of("店铺", "门店", "分店", "各店"),
              List.of("店", "店铺维度"),
              ""),
          new DimensionDefinition(
              "BUCKET_DATE",
              "日历日",
              "DATE",
              List.of("GTE", "LTE", "BETWEEN"),
              List.of("按天", "按日", "每天", "日历日", "按日期", "按日维度"),
              List.of("日期", "天"),
              ""),
          new DimensionDefinition(
              "CURRENCY",
              "币种",
              "TEXT",
              List.of("EQ", "IN"),
              List.of("币种", "货币"),
              List.of("currency"),
              ""));

  // ==========================================================================
  // 币种取值词表（**受控**的货币码 —— 不是任意字符串）
  // ==========================================================================

  /** 币种词 → 币种码（**封闭词表**；认不出来就没有币种筛选）。 */
  static final List<String[]> CURRENCY_VALUES =
      List.of(
          new String[] {"美元", "美金", "usd", "USD"},
          new String[] {"欧元", "eur", "EUR"},
          new String[] {"人民币", "元", "cny", "rmb", "CNY"},
          new String[] {"英镑", "gbp", "GBP"},
          new String[] {"日元", "jpy", "JPY"});

  // ==========================================================================
  // 时间范围（与 18.4.1.1 的口径码**逐字一致**）
  // ==========================================================================

  static final String LAST_7_DAYS = "LAST_7_DAYS";
  static final String LAST_30_DAYS = "LAST_30_DAYS";
  static final String LAST_WEEK = "LAST_WEEK";
  static final String THIS_MONTH = "THIS_MONTH";

  /** 默认时间范围（问句里没提时间时的兜底）。 */
  static final String DEFAULT_PERIOD = LAST_30_DAYS;

  // ==========================================================================
  // 聚合方式的**受控**补充词
  // ==========================================================================

  /** 求和类问法（只对 SUM 型指标成立）。 */
  static final List<String> SUM_KEYWORDS = List.of("合计", "总和", "总计", "加起来");

  /** 平均类问法 —— 语义层**没有**这个聚合口径 ⇒ 如实答不支持。 */
  static final List<String> AVG_KEYWORDS = List.of("平均", "均值", "人均");

  // ==========================================================================
  // 查表
  // ==========================================================================

  /** 按码取指标定义（找不到返回 {@code null}）。 */
  static MetricDefinition metric(String code) {
    if (code == null || code.isBlank()) return null;
    var normalized = code.trim().toUpperCase(Locale.ROOT);
    for (var definition : METRICS) {
      if (definition.code().equals(normalized)) return definition;
    }
    return null;
  }

  /** 按码取维度定义（找不到返回 {@code null}）。 */
  static DimensionDefinition dimension(String code) {
    if (code == null || code.isBlank()) return null;
    var normalized = code.trim().toUpperCase(Locale.ROOT);
    for (var definition : DIMENSIONS) {
      if (definition.code().equals(normalized)) return definition;
    }
    return null;
  }

  /** 一次确定性匹配的结果（分数 + 最长命中词长度 —— 两条都是确定的）。 */
  record Match<T>(T definition, int score, int longestKeyword) {

    boolean matched() {
      return definition != null && score > 0;
    }
  }

  /**
   * 按关键词匹配指标（**确定性**，不是模型理解）。
   *
   * <p>🔴 <b>只命中弱词不算数</b>：必须至少命中一个**强词**。
   * 同分时按「命中的最长关键词长度」裁决（「毛利率」比「毛利」长 ⇒ 毛利率胜出），
   * 再同长时按目录顺序（更具体的排前面）。
   */
  static Match<MetricDefinition> matchMetric(String question) {
    return match(question, METRICS, MetricDefinition::strongKeywords, MetricDefinition::weakKeywords);
  }

  /** 按关键词匹配维度（同 {@link #matchMetric} 的规则）。 */
  static Match<DimensionDefinition> matchDimension(String question) {
    return match(
        question, DIMENSIONS, DimensionDefinition::strongKeywords, DimensionDefinition::weakKeywords);
  }

  private static <T> Match<T> match(
      String question,
      List<T> definitions,
      java.util.function.Function<T, List<String>> strong,
      java.util.function.Function<T, List<String>> weak) {
    if (question == null || question.isBlank()) return new Match<>(null, 0, 0);
    var q = question.toLowerCase(Locale.ROOT);
    T best = null;
    var bestScore = 0;
    var bestLongest = 0;
    for (var definition : definitions) {
      var score = 0;
      var longest = 0;
      var strongHit = false;
      for (var keyword : strong.apply(definition)) {
        var normalized = keyword.toLowerCase(Locale.ROOT);
        if (q.contains(normalized)) {
          score += 3;
          strongHit = true;
          longest = Math.max(longest, normalized.length());
        }
      }
      for (var keyword : weak.apply(definition)) {
        var normalized = keyword.toLowerCase(Locale.ROOT);
        if (q.contains(normalized)) {
          score += 1;
          longest = Math.max(longest, normalized.length());
        }
      }
      // 🔴 **只命中弱词不算数**：没有强词 ⇒ 这个条目不是用户问的那个。
      if (!strongHit) continue;
      if (score > bestScore || (score == bestScore && longest > bestLongest)) {
        best = definition;
        bestScore = score;
        bestLongest = longest;
      }
    }
    return new Match<>(best, bestScore, bestLongest);
  }

  // ==========================================================================
  // 其它受控词
  // ==========================================================================

  /** 从问句里认出时间范围。 */
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

  /** 降序（从高到低）的受控词。 */
  static final List<String> DESC_KEYWORDS =
      List.of("最高", "最多", "最大", "最好", "从高到低", "降序", "排名靠前", "top", "排前面");

  /** 升序（从低到高）的受控词。 */
  static final List<String> ASC_KEYWORDS =
      List.of("最低", "最少", "最小", "最差", "从低到高", "升序", "排名靠后", "bottom", "排后面");

  /**
   * 从问句里认出排序方向。
   *
   * @return {@code DESC} / {@code ASC}；**没提排序时返回 {@code null}**
   *     ⇒ 交给调用方按「排名类问题默认从高到低」的兜底
   */
  static String sortDirectionOf(String question) {
    if (question == null) return null;
    var q = question.toLowerCase(Locale.ROOT);
    for (var keyword : ASC_KEYWORDS) {
      if (q.contains(keyword.toLowerCase(Locale.ROOT))) return "ASC";
    }
    for (var keyword : DESC_KEYWORDS) {
      if (q.contains(keyword.toLowerCase(Locale.ROOT))) return "DESC";
    }
    return null;
  }

  /**
   * 从问句里认出币种取值。
   *
   * @return 币种码（USD / EUR / …）；没提币种时返回 {@code null}
   */
  static String currencyOf(String question) {
    if (question == null) return null;
    var q = question.toLowerCase(Locale.ROOT);
    for (var pair : CURRENCY_VALUES) {
      for (int index = 0; index < pair.length - 1; index++) {
        if (q.contains(pair[index].toLowerCase(Locale.ROOT))) return pair[pair.length - 1];
      }
    }
    return null;
  }

  /** 问句里有没有「平均类」聚合词（语义层没有这个口径）。 */
  static boolean asksForAverage(String question) {
    return containsAny(question, AVG_KEYWORDS);
  }

  /** 问句里有没有「求和类」聚合词。 */
  static boolean asksForSum(String question) {
    return containsAny(question, SUM_KEYWORDS);
  }

  private static boolean containsAny(String question, List<String> keywords) {
    if (question == null || question.isBlank()) return false;
    var q = question.toLowerCase(Locale.ROOT);
    for (var keyword : keywords) {
      if (q.contains(keyword.toLowerCase(Locale.ROOT))) return true;
    }
    return false;
  }
}
