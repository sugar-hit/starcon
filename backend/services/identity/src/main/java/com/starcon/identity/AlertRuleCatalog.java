package com.starcon.identity;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * 1.3.2.1 预警规则 —— **预置规则集与指标词表**（系统事实，写在代码里）。
 *
 * <p>🔴 <b>预置规则集是代码里的声明，不是数据库里的数据源。</b>
 *
 * <p>蓝图原文：「系统预置跨境常用规则集，用户可改参数或关闭。」
 *
 * <p>照抄本仓既有做法（13 指标的 {@code ReportingMetricCatalog}：「口径唯一、
 * 租户改了就跟着改」）：预置规则**改一次对所有人生效**，绝不塞进数据库当数据源。
 * 数据库里只有三样东西 —— 租户的<b>参数覆盖</b>、租户<b>自建的规则</b>、
 * 租户的<b>关闭动作</b>。
 *
 * <p><b>为什么要这样分</b>：
 *
 * <ul>
 *   <li>预置集是<b>平台的产品决定</b>（「跨境常用」），它属于代码版本，
 *       不属于任何一个租户的数据 —— 落进租户表就得为每个租户复制一份，
 *       改一条预置规则要跑遍全库，还会出现「有的租户有、有的没有」。</li>
 *   <li>租户的<b>修改意图</b>（我不要 14 天了，我要 21 天）必须落库，
 *       否则用户改完刷新就没了。</li>
 *   <li>两边合成<b>生效值</b>：预置默认值 ⨯ 租户覆盖（覆盖优先），
 *       合并在服务层做，读生效值时 SQL 里<b>不许硬编码任何阈值</b>。</li>
 * </ul>
 *
 * <p><b>代价与收益</b>：预置集改了默认阈值，没动过那条的租户自动跟着变；
 * 改过的那条保留自己的覆盖 —— 那是用户显式表达过的意图，不该被系统默认值悄悄改掉。
 * 「恢复默认」= 删掉那行覆盖（{@code POST /identity/alert-rules/{id}/reset}）。
 *
 * <p><b>指标词表</b>：度量码沿用<b>跨服务同一套命名口径</b> ——
 * {@code SALES_AMOUNT} / {@code ORDER_COUNT} 等与 13 指标的
 * {@code ReportingMetricCatalog} <b>逐字同码</b>（同一个指标只允许一处定义，
 * 别处引用不允许重算）；07 库存 / 12 财务的指标沿用同一套命名风格。
 *
 * <p><b>指标可用性</b>：{@code available=false} 的指标是<b>上游还没交付那个口径</b>，
 * 不是「这个指标没有值」。写清楚 {@code ownerModule}（归谁）与原因，
 * 界面上才说得清「为什么这条规则算不出数」。
 */
public final class AlertRuleCatalog {

  private AlertRuleCatalog() {}

  // ==========================================================================
  // 一、单位
  // ==========================================================================

  /** 度量单位。 */
  public enum Unit {
    /** 金额（**带币种**，跨币种不可相加）。 */
    CURRENCY,
    /** 计数。 */
    COUNT,
    /** 数量。 */
    QUANTITY,
    /** 百分比（变化率类阈值用这个）。 */
    PERCENT,
    /** 天数。 */
    DAYS
  }

  /** 单位中文名。 */
  public static String unitLabel(Unit unit) {
    return switch (unit) {
      case CURRENCY -> "金额";
      case COUNT -> "计数";
      case QUANTITY -> "数量";
      case PERCENT -> "百分比";
      case DAYS -> "天数";
    };
  }

  /**
   * 阈值单位由「度量 + 比较基准」推出，<b>不单独存</b>。
   *
   * <p>环比 / 同比比较的是<b>变化率</b>，单位恒为百分比 ——
   * 「销售额环比跌超 20%」的阈值单位是百分比，而它比较的度量（销售额）单位是金额。
   * 把单位存成列就会出现两列各说一半，改了度量忘了改单位。
   */
  public static Unit thresholdUnit(MetricDefinition metric, String changeBasis) {
    return ChangeBasis.PERIOD_OVER_PERIOD.equals(changeBasis)
            || ChangeBasis.YEAR_OVER_YEAR.equals(changeBasis)
        ? Unit.PERCENT
        : metric.unit();
  }

  // ==========================================================================
  // 二、比较基准（受控词表）
  // ==========================================================================

  /** 比较基准词表。 */
  public static final class ChangeBasis {
    private ChangeBasis() {}

    /** 直接比较度量值。 */
    public static final String NONE = "NONE";
    /** 环比（与上一个等长周期比）。 */
    public static final String PERIOD_OVER_PERIOD = "PERIOD_OVER_PERIOD";
    /** 同比（与去年同期比）。 */
    public static final String YEAR_OVER_YEAR = "YEAR_OVER_YEAR";

    /** 全部取值（受控词表，与 V384 的 CHECK 约束逐字同词表）。 */
    public static final List<String> ALL = List.of(NONE, PERIOD_OVER_PERIOD, YEAR_OVER_YEAR);

    /** 中文名。 */
    public static String label(String code) {
      return switch (code) {
        case NONE -> "直接比较";
        case PERIOD_OVER_PERIOD -> "环比";
        case YEAR_OVER_YEAR -> "同比";
        default -> code;
      };
    }
  }

  // ==========================================================================
  // 三、指标词表
  // ==========================================================================

  /**
   * 一个受控指标的词表条目。
   *
   * @param metricCode 指标码（跨服务唯一口径）
   * @param displayName 中文名
   * @param unit 单位
   * @param ownerSystem 归属系统（谁拥有这个指标的事实）
   * @param ownerModule 归属模块（该系统下的哪一格）
   * @param applicableObjectTypes 这个指标**能挂在哪些对象类型上**（受控词表）
   * @param available 上游是否**已交付**这个指标口径
   * @param unavailableReason 没接通时写清缺什么（available=true 时为 null）
   */
  public record MetricDefinition(
      String metricCode,
      String displayName,
      Unit unit,
      List<String> applicableObjectTypes,
      String ownerSystem,
      String ownerModule,
      boolean available,
      String unavailableReason) {}

  /** 指标词表（受控，与 V384 的 CHECK 约束逐字同词表）。 */
  public static final List<MetricDefinition> METRICS =
      List.of(
          // ---- 13 指标（与 ReportingMetricCatalog 逐字同码）----
          new MetricDefinition(
              "SALES_AMOUNT",
              "销售额",
              Unit.CURRENCY, List.of("SHOP","LEGAL_ENTITY"),
              "13 指标",
              "13.1.1.1 经营总览",
              true,
              null),
          new MetricDefinition(
              "ORDER_COUNT", "订单量", Unit.COUNT, List.of("SHOP","LEGAL_ENTITY"), "13 指标", "13.1.1.1 经营总览", true, null),
          new MetricDefinition(
              "UNIT_SOLD", "销量", Unit.QUANTITY, List.of("SHOP","LEGAL_ENTITY"), "13 指标", "13.1.1.1 经营总览", true, null),
          new MetricDefinition(
              "COGS_AMOUNT",
              "成本",
              Unit.CURRENCY, List.of("SHOP","LEGAL_ENTITY"),
              "13 指标",
              "13.1.1.1 经营总览",
              true,
              null),
          new MetricDefinition(
              "GROSS_PROFIT",
              "毛利额",
              Unit.CURRENCY, List.of("SHOP","LEGAL_ENTITY"),
              "13 指标",
              "13.1.1.1 经营总览",
              true,
              null),
          new MetricDefinition(
              "GROSS_MARGIN",
              "毛利率",
              Unit.PERCENT, List.of("SHOP","LEGAL_ENTITY"),
              "13 指标",
              "13.1.1.1 经营总览",
              true,
              null),
          new MetricDefinition(
              "AD_SPEND", "广告花费", Unit.CURRENCY, List.of("SHOP","LEGAL_ENTITY"), "13 指标", "13.1.1.1 经营总览", true, null),
          new MetricDefinition(
              "NET_PROFIT", "净利", Unit.CURRENCY, List.of("LEGAL_ENTITY"), "13 指标", "13.1.1.1 经营总览", true, null),
          new MetricDefinition(
              "RETURNED_AMOUNT",
              "退货额",
              Unit.CURRENCY, List.of("SHOP","LEGAL_ENTITY"),
              "13 指标",
              "13.1.1.1 经营总览",
              true,
              null),
          new MetricDefinition(
              "RETURN_RATE", "退货率", Unit.PERCENT, List.of("SHOP","LEGAL_ENTITY"), "13 指标", "13.1.1.1 经营总览", true, null),
          new MetricDefinition(
              "INVENTORY_VALUE",
              "库存总额",
              Unit.CURRENCY, List.of("LEGAL_ENTITY"),
              "13 指标",
              "13.1.1.1 经营总览",
              true,
              null),
          new MetricDefinition(
              "RECEIVABLE_BALANCE",
              "应收余额",
              Unit.CURRENCY, List.of("LEGAL_ENTITY"),
              "13 指标 / 12 财务",
              "13.1.1.1 经营总览 / 12.1.1.2 应收账龄",
              true,
              null),
          // ---- 12 应收 ----
          new MetricDefinition(
              "RECEIVABLE_OVERDUE_DAYS",
              "应收超期天数",
              Unit.DAYS, List.of("LEGAL_ENTITY"),
              "12 财务",
              "12.1.1.2 应收账龄",
              true,
              null),
          new MetricDefinition(
              "RECEIVABLE_OVERDUE_AMOUNT",
              "应收超期金额",
              Unit.CURRENCY, List.of("LEGAL_ENTITY"),
              "12 财务",
              "12.1.1.2 应收账龄",
              true,
              null),
          // ---- 12 应付 ----
          new MetricDefinition(
              "PAYABLE_OVERDUE_DAYS",
              "应付超期天数",
              Unit.DAYS, List.of("SUPPLIER"),
              "12 财务",
              "12.1.2.1 应付单管理",
              true,
              null),
          new MetricDefinition(
              "PAYABLE_OVERDUE_AMOUNT",
              "应付超期金额",
              Unit.CURRENCY, List.of("SUPPLIER"),
              "12 财务",
              "12.1.2.1 应付单管理",
              true,
              null),
          // ---- 07 库存 ----
          new MetricDefinition(
              "STOCK_COVER_DAYS",
              "库存可覆盖天数",
              Unit.DAYS, List.of("SKU"),
              "07 库存",
              "7.5.1.2 在途库存（库存风险投影）",
              false,
              "覆盖天数口径（库存量 ÷ 日均出库速率）尚未交付：07 库存当前只有库存数量事实投影，"
                  + "没有日均速率投影，所以「还能卖几天」这个数今天算不出来。"
                  + "在它接通之前，用它建的规则引擎求值会判为「不可判定」，而不是「低于阈值」。"));

  /** 指标码 → 词表条目。 */
  public static MetricDefinition metric(String metricCode) {
    for (MetricDefinition metric : METRICS) {
      if (metric.metricCode().equals(metricCode)) return metric;
    }
    return null;
  }

  /**
   * 这个指标能不能挂在某个对象类型上。
   *
   * <p>「店铺库存可覆盖天数」「供应商销售额」这类组合在数据上说得通、在业务上没意义 ——
   * 与其等到引擎求值时才发现算不出来，不如建规则时就拒掉。
   */
  public static boolean appliesTo(MetricDefinition metric, String objectType) {
    return metric != null && metric.applicableObjectTypes().contains(objectType);
  }

  // ==========================================================================
  // 四、预置规则集
  // ==========================================================================

  /**
   * 一条预置规则。
   *
   * @param ruleCode 规则码（租户覆盖行用它定位）
   * @param displayName 中文名
   * @param description 说明（界面上要显示给人看）
   * @param objectType 对象类型（SKU / SHOP / LEGAL_ENTITY / SUPPLIER）
   * @param metricCode 度量码
   * @param threshold 默认阈值
   * @param timeWindow 时间窗
   * @param comparison 比较方向
   * @param changeBasis 比较基准（直接比较 / 环比 / 同比）
   * @param objectScope 默认对象范围（ALL / SPECIFIC）
   * @param defaultTimezone 默认时区（时间窗边界所按时区；**不写死 UTC**）
   * @param upstreamSystem 上游系统
   * @param upstreamModule 上游模块
   * @param upstreamEndpoint 上游只读端点
   */
  public record PresetRule(
      String ruleCode,
      String displayName,
      String description,
      String objectType,
      String metricCode,
      BigDecimal threshold,
      String timeWindow,
      String comparison,
      String changeBasis,
      String objectScope,
      String defaultTimezone,
      String upstreamSystem,
      String upstreamModule,
      String upstreamEndpoint) {}

  /**
   * 预置规则集：**跨境常用**的几条。
   *
   * <p>蓝图的三个例子（某 SKU 库存低于 N 天、某店铺销售额环比跌超 X%、应收超期 Y 天）
   * 逐条落在这里，另加两条同类常用线。
   *
   * <p><b>默认时区</b>：{@code Asia/Shanghai}（本仓平台默认展示时区，
   * {@code AccountLocaleRules.DEFAULT_TIMEZONE}）—— <b>不是 UTC</b>：
   * 「今天」按 UTC 算会和跨境运营看到的自然日差一天。
   * 租户可以逐条改。
   */
  public static final List<PresetRule> PRESETS =
      List.of(
          new PresetRule(
              "SKU_STOCK_COVER_DAYS_LOW",
              "SKU 库存可覆盖天数偏低",
              "某个 SKU 的库存按当前出库速率还能卖不到 N 天时预警 —— 跨境补货最常用的一条线。",
              "SKU",
              "STOCK_COVER_DAYS",
              new BigDecimal("14"),
              "INSTANT",
              "LT",
              ChangeBasis.NONE,
              "ALL",
              "Asia/Shanghai",
              "07 库存",
              "7.5.1.2 在途库存（库存风险投影）",
              "warehouse/inventory-risk/projection"),
          new PresetRule(
              "SHOP_SALES_AMOUNT_DROP",
              "店铺销售额环比下跌",
              "某家店铺的自然月销售额比上一个等长周期跌超 X% 时预警。",
              "SHOP",
              "SALES_AMOUNT",
              new BigDecimal("20"),
              "CALENDAR_MONTH",
              "LT",
              ChangeBasis.PERIOD_OVER_PERIOD,
              "ALL",
              "Asia/Shanghai",
              "13 指标",
              "13.1.1.1 经营总览",
              "reporting/overview"),
          new PresetRule(
              "LEGAL_ENTITY_RECEIVABLE_OVERDUE_DAYS",
              "法人应收超期天数",
              "某个法人的应收款已逾期 N 天时预警 —— 回款风险最常用的一条线。",
              "LEGAL_ENTITY",
              "RECEIVABLE_OVERDUE_DAYS",
              new BigDecimal("30"),
              "INSTANT",
              "GE",
              ChangeBasis.NONE,
              "ALL",
              "Asia/Shanghai",
              "12 财务",
              "12.1.1.2 应收账龄",
              "finance/receivable-aging/overdue"),
          new PresetRule(
              "SHOP_GROSS_MARGIN_DROP",
              "店铺毛利率环比下滑",
              "某家店铺的自然月毛利率比上一个等长周期跌超 X 个百分点时预警。",
              "SHOP",
              "GROSS_MARGIN",
              new BigDecimal("5"),
              "CALENDAR_MONTH",
              "LT",
              ChangeBasis.PERIOD_OVER_PERIOD,
              "ALL",
              "Asia/Shanghai",
              "13 指标",
              "13.1.1.1 经营总览",
              "reporting/overview"),
          new PresetRule(
              "SUPPLIER_PAYABLE_OVERDUE_DAYS",
              "供应商应付超期天数",
              "某个供应商的应付款已逾期 N 天时预警 —— 供应商账期与合作风险。",
              "SUPPLIER",
              "PAYABLE_OVERDUE_DAYS",
              new BigDecimal("30"),
              "INSTANT",
              "GE",
              ChangeBasis.NONE,
              "ALL",
              "Asia/Shanghai",
              "12 财务",
              "12.1.2.1 应付单管理",
              "finance/payables/unpaid"));

  /** 规则码 → 预置定义。 */
  public static PresetRule preset(String ruleCode) {
    for (PresetRule preset : PRESETS) {
      if (preset.ruleCode().equals(ruleCode)) return preset;
    }
    return null;
  }

  /**
   * 预置规则的**确定性合成 id**。
   *
   * <p>为什么要合成一个 id：预置规则**没被本租户改过时，库里根本没有行**，
   * 也就没有行 id —— 而「改参数」这个动作必须能通过 {@code PUT /{id}} 定位到它。
   *
   * <p>合成 id 由规则码派生（{@code UUID.nameUUIDFromBytes}，MD5 型 v3），
   * <b>跨进程、跨重启恒定</b>，所以同一个预置规则在任何时候拿到的 id 都一样，
   * 前端拿到的 id 可以直接拿去 PUT / disable / enable。
   *
   * <p>它<b>不是</b>数据库主键：库里那一行（被改过之后）有自己的 id，
   * 两种 id 都能定位到同一条规则（见 {@code AlertRuleService} 的解析逻辑）。
   */
  public static UUID presetRowId(String ruleCode) {
    return UUID.nameUUIDFromBytes(
        ("alert-rule:preset:" + ruleCode).getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  /** 合成 id → 规则码（不是预置规则返回 null）。 */
  public static String presetCodeOf(UUID syntheticId) {
    if (syntheticId == null) return null;
    for (PresetRule preset : PRESETS) {
      if (presetRowId(preset.ruleCode()).equals(syntheticId)) return preset.ruleCode();
    }
    return null;
  }

  /** 预置规则的默认时区（本仓平台默认展示时区，**不写死 UTC**）。 */
  public static String defaultTimezone() {
    return AccountLocaleRules.DEFAULT_TIMEZONE;
  }
}
