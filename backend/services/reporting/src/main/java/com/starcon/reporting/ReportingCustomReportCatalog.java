package com.starcon.reporting;

import java.util.List;
import java.util.Locale;

/**
 * 13.6.1.1 自定义报表的**受控语义层目录**。
 *
 * <p>🔴 **本类就是「不开放任意 SQL」的物理边界**：报表配置里能出现的维度码、运算符、
 * 图表类型，**全部**是这个枚举里的常量。服务层拼装查询时，SQL 片段**只从这些常量里取**，
 * 用户输入**只作为绑定参数**（{@code ?}）传进去 —— 任何时候都不把用户输入拼进 SQL 文本。
 *
 * <p>蓝图原文（13.6.1.1 关系句）：
 *
 * <blockquote>
 * 「**只允许基于语义层指标自助，不开放任意 SQL** —— 否则报表口径会分裂成几十个版本，
 * 最终没人知道该信哪个。」
 * </blockquote>
 *
 * <p>⇒ 维度白名单 = **投影事实表 {@code reporting_metric_fact} 上真实存在的列**。
 * 不是「让用户填任意列名」，也不是「让用户写 {@code where} 子句」。
 *
 * <p>⚠️ ** LEGAL_ENTITY / SHOP 维度出的是 uuid 文本**：语义层（V376）不带法人 / 店铺的
 * **名称档案**，所以报表给 uuid，**不编一个名字**（编名字 = 第二个真相）。
 */
public final class ReportingCustomReportCatalog {

  private ReportingCustomReportCatalog() {}

  // ==========================================================================
  // 维度白名单
  // ==========================================================================

  /**
   * 可用的维度（**白名单**）。
   *
   * <p>⚠️ **枚举常量里的 SQL 片段是代码里的常量**，不是用户输入拼出来的：
   * {@link #expression(String)} 只会产出 {@code 别名.列名::类型} 这种形状，
   * 列名与类型都写死在这个枚举里。
   */
  public enum Dimension {
    /** 公司 / 法人 / 店铺（投影事实的 {@code scope_type} 列）。 */
    SCOPE_TYPE("scope_type", null, "公司 / 法人 / 店铺", "TEXT"),
    /** 法人（uuid 文本；语义层不带法人名称）。 */
    LEGAL_ENTITY("legal_entity_id", "uuid", "法人（uuid）", "UUID"),
    /** 店铺（uuid 文本；语义层不带店铺名称）。 */
    SHOP("shop_id", "uuid", "店铺（uuid）", "UUID"),
    /** 日历日（事实的分桶日）。 */
    BUCKET_DATE("bucket_date", "date", "日历日", "DATE"),
    /** 币种。 */
    CURRENCY("currency", null, "币种", "TEXT");

    private final String column;
    private final String cast;
    private final String label;
    private final String kind;

    Dimension(String column, String cast, String label, String kind) {
      this.column = column;
      this.cast = cast;
      this.label = label;
      this.kind = kind;
    }

    /** 列名（**代码里的常量**）。 */
    public String column() {
      return column;
    }

    /** 中文标签。 */
    public String label() {
      return label;
    }

    /** 取值形态：TEXT / UUID / DATE。 */
    public String kind() {
      return kind;
    }

    /** SQL 表达式：{@code 别名.列名::类型}（类型为空时就是 {@code 别名.列名}）。 */
    public String expression(String alias) {
      return cast == null ? alias + "." + column : alias + "." + column + "::" + cast;
    }

    /**
     * 绑定参数的显式 cast。
     *
     * <p>⚠️ **必须写显式 cast**：jOOQ 把 {@code String} 绑成 {@code varchar}，
     * {@code date >= varchar} 在 PostgreSQL 里没有运算符 ⇒ 直接报错。
     * 写成 {@code ?::date} / {@code ?::text} 就不会有这个问题，
     * 也**杜绝了「值被当成 SQL 片段」的可能**（它永远只是个字面量）。
     *
     * <p>⚠️ **UUID 维度必须 cast 成 {@code uuid}**：{@link Dimension#expression(String)} 给
     * LEGAL_ENTITY / SHOP 产出的是 {@code f.shop_id::uuid}，而 PostgreSQL **没有**
     * {@code uuid = text} 这个运算符 ⇒ 写成 {@code ?::text} 会直接报
     * 「operator does not exist: uuid = text」。
     * （13.6.1.1 交付时只测过 CURRENCY 这个文本维度，UUID 维度这条路径没被走到；
     * 13.6.1.2 看板搭建按「店铺 / 法人」筛组件时撞上了它，这里一并修好。）
     */
    public String valueCast() {
      if ("DATE".equals(kind)) return "date";
      if ("UUID".equals(kind)) return "uuid";
      return "text";
    }

    /** 按码取维度；取不到抛 {@link IllegalArgumentException}（不静默回落）。 */
    public static Dimension of(String code) {
      if (code == null || code.isBlank()) {
        throw new IllegalArgumentException("维度码不能为空");
      }
      var normalized = code.trim().toUpperCase(Locale.ROOT);
      for (var dimension : values()) {
        if (dimension.name().equals(normalized)) return dimension;
      }
      throw new IllegalArgumentException(
          "维度码只能是 "
              + java.util.Arrays.stream(values()).map(Enum::name).toList()
              + "（收到的是「" + code + "」）；自定义报表**不开放任意列名**。");
    }
  }

  // ==========================================================================
  // 筛选运算符
  // ==========================================================================

  /**
   * 筛选运算符（**白名单**）。
   *
   * <p>每个运算符只负责把「维度表达式 + 绑定参数」变成一段 SQL 片段，
   * **值永远进绑定参数**，不进 SQL 文本。
   */
  public enum FilterOperator {
    /** 等于。 */
    EQ,
    /** 属于（逗号分隔的多个值）。 */
    IN,
    /** 不早于（≥）。 */
    GTE,
    /** 不晚于（≤）。 */
    LTE,
    /** 介于（含两端）。 */
    BETWEEN;

    /** 中文标签。 */
    public String label() {
      return switch (this) {
        case EQ -> "等于";
        case IN -> "属于";
        case GTE -> "不早于";
        case LTE -> "不晚于";
        case BETWEEN -> "介于";
      };
    }

    /** 按码取运算符；取不到抛异常（不静默回落成「等于」）。 */
    public static FilterOperator of(String code) {
      if (code == null || code.isBlank()) {
        throw new IllegalArgumentException("筛选运算符不能为空");
      }
      var normalized = code.trim().toUpperCase(Locale.ROOT);
      for (var operator : values()) {
        if (operator.name().equals(normalized)) return operator;
      }
      throw new IllegalArgumentException(
          "筛选运算符只能是 EQ / IN / GTE / LTE / BETWEEN（收到的是「" + code + "」）。");
    }
  }

  // ==========================================================================
  // 图表类型
  // ==========================================================================

  /**
   * 图表类型（**白名单**）。
   *
   * <p>⚠️ **形状约束是真的**：饼图只能一维一度量（多个度量没法放进一个饼），
   * 柱状 / 折线至少要一个维度（没有维度的折线只有一根线，看不出趋势）。
   * 不满足时服务层**报 400 并说清差在哪**，不静默回落成表格。
   */
  public enum ChartType {
    /** 表格（任何形状都能画）。 */
    TABLE,
    /** 柱状图（至少一个维度）。 */
    BAR,
    /** 折线图（至少一个维度）。 */
    LINE,
    /** 饼图（恰好一个维度 + 一个度量）。 */
    PIE;

    public String label() {
      return switch (this) {
        case TABLE -> "表格";
        case BAR -> "柱状图";
        case LINE -> "折线图";
        case PIE -> "饼图";
      };
    }

    /** 按码取图表类型；取不到抛异常。 */
    public static ChartType of(String code) {
      if (code == null || code.isBlank()) return TABLE;
      var normalized = code.trim().toUpperCase(Locale.ROOT);
      for (var type : values()) {
        if (type.name().equals(normalized)) return type;
      }
      throw new IllegalArgumentException(
          "图表类型只能是 TABLE / BAR / LINE / PIE（收到的是「" + code + "」）。");
    }

    /** 校验「维度数 / 度量数」是否满足这个图表类型的形状约束。 */
    public void validateShape(int dimensionCount, int measureCount) {
      switch (this) {
        case TABLE -> {}
        case BAR, LINE -> {
          if (dimensionCount < 1) {
            throw new IllegalArgumentException(
                type() + " 至少要一个维度（没有维度的" + type() + "看不出任何分组）。");
          }
        }
        case PIE -> {
          if (dimensionCount != 1 || measureCount != 1) {
            throw new IllegalArgumentException(
                "饼图只能一维一度量（现在 " + dimensionCount + " 维 " + measureCount + " 度量）。");
          }
        }
      }
    }

    private String type() {
      return this == BAR ? "柱状图" : "折线图";
    }
  }

  // ==========================================================================
  // 能力声明里的静态事实
  // ==========================================================================

  /** 本服务**真的读过**的上游模块（都是本服务内的已交付模块）。 */
  public static final List<String> UPSTREAM_MODULES =
      List.of(
          "13.1.1.1 经营总览 · 指标口径唯一表（reporting_metric_definition）—— 本模块的**唯一数据源目录**",
          "13.1.1.1 经营总览 · 投影事实（reporting_metric_fact）—— 本模块**唯一的数据来源**");

  /**
   * **确实拿不到**的部分（每条都写清原因与影响）。
   *
   * <p>🔴 **导出与实际投递都属 13.6.2.1（未交付）**：本模块只登记「订阅计划」与
   * 「待投递记录」，**不假装已经能导出 / 已经收到邮件**。
   */
  public static final List<String> NOT_DELIVERED =
      List.of(
          "🔴 **大文件导出（Excel / CSV / PDF）未交付** —— 归属 13.6.2.1 导出与订阅。"
              + "蓝图关系句写死「大导出走异步任务并给出有效期」⇒ 那需要**异步任务表 + 文件有效期**，"
              + "本模块**没有**导出端点，也没有导出任务表。界面上「导出」明确写「未交付」，不提供假按钮。",
          "🔴 **邮件 / 企业 IM 的实际投递未交付** —— 归属 13.6.2.1。"
              + "本模块只负责「订阅计划（cron + 通道 + 收件目标）」与「**待投递记录**（队列）」；"
              + "队列里的记录状态是 PENDING（待投递），**不是「已发送」**。",
          "🔴 **不开放任意 SQL** —— 蓝图关系句的硬约束。报表的度量只能引用"
              + "**已登记的 metric_code**（口径唯一），维度只能是 5 个白名单维度码，"
              + "筛选值一律走**绑定参数**。用户输入**永远不会**被拼进 SQL 文本。",
          "🔴 **法人 / 店铺维度给的是 uuid 文本，不是名称** —— 语义层（13.1.1.1 的口径表与投影事实）"
              + "不带法人 / 店铺的名称档案 ⇒ 报表里给 uuid，**不编一个名字**。",
          "**派生型指标按登记口径在服务端算** —— DERIVED_RATIO / DERIVED_DIFF 只写分子分母引用，"
              + "服务端**分别取数再算**；🔴 **分母为 0 ⇒ 比率为 NULL（不可比），不是 0**。",
          "**跨币种合计** —— 涉币指标按币种分别报（绝不跨币种相加）；"
              + "与货币无关的量（笔数 / 件数）给一个跨币种合计，它的币种是**空串**（空串 = 与币种无关）。",
          "**看板搭建（13.6.1.2）未交付** —— 本模块只提供「报表」这一层，"
              + "看板（拖拽组件 / 布局 / 角色可见范围）不在本模块范围内。",
          "**外部调度触发投递队列** —— 本服务**不装 @Scheduled**（与 channel / identity 同一纪律："
              + "多副本下自带定时器要做主节点选举与分布式锁）⇒ 订阅计划的「到点落成待投递记录」"
              + "由外部调度调用 `POST /reporting/custom-report/deliveries/tick` 触发。");

  /** 判定规则与口径说明（页面上照着显示）。 */
  public static final List<CustomReportRuleNote> RULES =
      List.of(
          new CustomReportRuleNote(
              "SEMANTIC_LAYER_ONLY",
              "🔴 **只允许基于语义层指标自助，不开放任意 SQL**："
                  + "每个度量都必须能追到一个**已登记的 metric_code**（口径唯一，"
                  + "13.1.1.1 的 reporting_metric_definition 是唯一口径表）；"
                  + "维度与筛选只能取白名单里的 5 个维度码；筛选值**只作为绑定参数**传进预编译 SQL，"
                  + "**绝不拼进 SQL 文本**。口径改一处，所有引用它的报表自动跟着改。"),
          new CustomReportRuleNote(
              "DERIVED_RATIO",
              "派生型指标（DERIVED_RATIO / DERIVED_DIFF）**按登记表里的分子分母分别取数再在服务端算**，"
                  + "不会自己挑别的列凑一个「差不多的比率」。"
                  + "🔴 **分母为 0 或缺数据 ⇒ 比率为 NULL（不可比）**，不是 0 —— "
                  + "「压根算不出来」与「算出来是零」在报表里是相反的两件事。"),
          new CustomReportRuleNote(
              "CURRENCY",
              "🔴 **涉币指标不跨币种相加**：按币种分别报（USD 一行、EUR 一行）。"
                  + "与货币无关的量（笔数 / 件数）给一个跨币种合计，它的币种显示为空串 —— "
                  + "空串 = 与币种无关，不是「币种未知」。"),
          new CustomReportRuleNote(
              "STOCK_METRIC",
              "存量型指标（LAST，如应收余额 / 库存总额）取**窗口末之前最近一天的快照**"
                  + "（余额不能累加）。这个口径与 13.1.1.1 经营总览**完全一致**，"
                  + "同一个指标在两处不会给出两个数。"),
          new CustomReportRuleNote(
              "SHARE_SCOPE",
              "🔴 **分享链接不等于公开访问**：令牌绑定租户 + 绑定报表归属（个人 / 团队）；"
                  + "未授权访问与「报表不存在」返回**逐字相同**的 404（不泄露存在性）。"),
          new CustomReportRuleNote(
              "DATA_LATENCY",
              "数据延迟：报表是投影，不是实时的。页面上常驻显示**数据更新时间**"
                  + "（最近一次投影落库时刻）；空表时是 null（不是一个长得像时间的假值）。"
                  + "报表数字与业务系统冲突时，**以业务系统为准**。"));

  /** 一条规则 / 口径说明。 */
  public record CustomReportRuleNote(String ruleCode, String description) {}
}
