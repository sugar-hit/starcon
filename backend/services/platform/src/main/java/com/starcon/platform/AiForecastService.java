package com.starcon.platform;

import static com.starcon.platform.AiForecastModels.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 18.4.2.2「选品与需求预测」的服务层。
 *
 * <p>数据访问一律走 <b>jOOQ 显式 SQL</b>（AGENTS.md：不用 JPA 懒加载掩盖关键逻辑）。
 * 租户上下文由 {@link AccessService#run} 绑定 —— 本类<b>不</b>读任何请求头里的租户。
 *
 * <p><b>六条纪律：</b>
 *
 * <ol>
 *   <li><b>每条预测都带区间与影响因素</b>：{@code point / lower / upper} 三个数
 *       <b>缺一不可</b>；影响因素逐条带方向、取值与口径说明。
 *       样本不足的 SKU <b>根本不进结果表</b>（它进的是「跳过计数 + 原因」）——
 *       一个拍脑袋的窄区间比没有区间更危险。
 *   <li><b>建议只产出，不执行</b>：本模块只落自己的建议台账，
 *       状态机只有 SUGGESTED / ADOPTED / REJECTED，<b>没有 EXECUTED</b> ——
 *       补货单归 6 采购、调价归 5 定价，跨服务写它们的库是被禁止的。
 *   <li><b>上游读不到就如实说读不到</b>：库存 / 在途读不到时<b>不生成补货建议</b>，
 *       绝不拿 0 冒充「库存为 0」；上一次运行的连通性落在运行记录里。
 *   <li><b>算法可解释</b>：移动平均 / 指数平滑 + 促销因子，口径与参数逐条写进
 *       {@link #capabilities()}，页面上照着显示。
 *   <li><b>促销影响必须说得出出处</b>：促销日历是租户自己的业务事实，
 *       每条促销都带录入方式（MANUAL / IMPORT）与预期提升率。
 *   <li><b>写入归属会话租户</b>：所有 INSERT 的 {@code tenant_id} 取自已认证会话，
 *       不接受请求参数传入；RLS 的 WITH CHECK 与触发器再兜一层。
 * </ol>
 */
@Service
public class AiForecastService {

  // ================================================================ 口径常量

  /** 列表分页上限（真分页的上限，防止一次拉爆）。 */
  static final int MAX_PAGE_SIZE = 100;

  /** 🔴 **样本天数下限**：低于这个数就算不出有意义的残差与区间 ⇒ 不给预测。 */
  static final int MIN_SAMPLE_DAYS = 4;

  /** 默认统计窗口（天）。 */
  static final int DEFAULT_WINDOW_DAYS = 30;

  /** 默认预测期（天）。 */
  static final int DEFAULT_HORIZON_DAYS = 30;

  /** 默认置信水平。 */
  static final BigDecimal DEFAULT_CONFIDENCE = new BigDecimal("0.95");

  /** 默认移动平均天数。 */
  static final int DEFAULT_MA_WINDOW = 3;

  /** 默认指数平滑 α。 */
  static final BigDecimal DEFAULT_ALPHA = new BigDecimal("0.3");

  /** 🔴 **爆品阈值**：统计窗口内销量达到这个数才算「有量」。 */
  static final BigDecimal HOT_UNITS_THRESHOLD = new BigDecimal("100");

  /** 🔴 **爆品增长率阈值**：相对上一个等长窗口的增长率。 */
  static final BigDecimal HOT_GROWTH_THRESHOLD = new BigDecimal("0.20");

  /** 🔴 **机会品趋势阈值**：近 7 天日均 ÷ 前 7 天日均。 */
  static final BigDecimal OPPORTUNITY_TREND_THRESHOLD = new BigDecimal("1.30");

  /** 🔴 **机会品基线阈值**：近期日均销量至少要到这个数（1 件/天）。 */
  static final BigDecimal OPPORTUNITY_MIN_DAILY_UNITS = BigDecimal.ONE;

  /** 🔴 **给调价建议的弹性门槛**：弹性 ≤ 这个值（降价确实能带量）才给建议。 */
  static final BigDecimal PRICE_ELASTICITY_THRESHOLD = new BigDecimal("-0.50");

  /** 🔴 **建议调价幅度上限**（保守试探，不是「一次调到最优」）。 */
  static final BigDecimal MAX_PRICE_ADJUST_RATE = new BigDecimal("0.10");

  /** 机会品 / 趋势判定用的两个子窗口（天）。 */
  static final int RECENT_WINDOW_DAYS = 7;

  // ================================================================ 依赖

  private final DSLContext sql;
  private final ForecastSalesPort sales;
  private final WarehouseInventoryPort inventory;
  private final ForecastInTransitPort inTransit;
  private final ForecastPurchasePort purchase;

  public AiForecastService(
      DSLContext sql,
      ForecastSalesPort sales,
      WarehouseInventoryPort inventory,
      ForecastInTransitPort inTransit,
      ForecastPurchasePort purchase) {
    this.sql = sql;
    this.sales = sales;
    this.inventory = inventory;
    this.inTransit = inTransit;
    this.purchase = purchase;
  }

  // ================================================================ 工具

  private static void check(boolean condition, String message) {
    if (!condition) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  /** 状态冲突（409）：不是「参数不对」，是「当前状态不允许」。 */
  private static void conflict(String message) {
    throw new ResponseStatusException(HttpStatus.CONFLICT, message);
  }

  private static String required(String value, String label, int max) {
    var trimmed = value == null ? "" : value.trim();
    check(!trimmed.isEmpty(), label + "不能为空");
    check(trimmed.length() <= max, label + "长度不能超过 " + max + " 个字符");
    return trimmed;
  }

  private static String optional(String value, String label, int max) {
    var trimmed = value == null ? "" : value.trim();
    check(trimmed.length() <= max, label + "长度不能超过 " + max + " 个字符");
    return trimmed;
  }

  private UUID currentTenant() {
    // 事务局部 GUC：租户由 AccessService 绑定，这里读的是**同一个**绑定。
    var raw =
        sql.fetchOne("select current_setting('app.current_tenant', true) as t").get(0, String.class);
    check(raw != null && !raw.isBlank(), "缺少可信租户上下文");
    return UUID.fromString(raw);
  }

  private long count(String sqlText, Object... params) {
    return sql.fetchOne(sqlText, params).get(0, Long.class);
  }

  private int pageSize(int size) {
    return Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
  }

  private static BigDecimal num(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }

  private static BigDecimal scale6(BigDecimal value) {
    return value.setScale(6, RoundingMode.HALF_UP);
  }

  /** 置信水平 → z 值（**只认这三个**，别让人传 0.83 然后得到一个说不清的区间）。 */
  static BigDecimal zFor(BigDecimal confidence) {
    var level = confidence.stripTrailingZeros();
    if (level.compareTo(new BigDecimal("0.90")) == 0) return new BigDecimal("1.645");
    if (level.compareTo(new BigDecimal("0.95")) == 0) return new BigDecimal("1.960");
    if (level.compareTo(new BigDecimal("0.99")) == 0) return new BigDecimal("2.576");
    return null;
  }

  // ================================================================ 能力声明

  /**
   * 18.4.2.2 的能力声明：逐条说清「有没有、依据是什么、缺了会怎样」。
   *
   * <p>🔴 **算法口径逐条报出**（不是「用了 AI 预测」这种空话）：移动平均与指数平滑的
   * 定义、参数、区间怎么来的，全部写在这里，页面直接显示。
   */
  public AiForecastCapabilities capabilities() {
    var capabilities =
        List.of(
            new AiForecastCapability(
                "18.4.2.2",
                "销量预测（确定性算法 · 可解释）",
                true,
                "移动平均（最近 maWindowDays 天均值）/ 指数平滑（level = α·当日 + (1-α)·上一期水平）；"
                    + "平台服务 platform_forecast_result 表落点估计 + 置信区间 + 影响因素",
                "",
                "🔴 **不是 AI 大模型**：本仓没有任何外部 AI / LLM 供应商适配器，"
                    + "预测用的是可复算的确定性算法，参数与口径逐条列在 algorithms / rules 里"),
            new AiForecastCapability(
                "18.4.2.2",
                "**置信区间**（下界 / 上界）",
                true,
                "point ± z × σ × √预测天数；σ = 一步预测误差的样本标准差（分母 n-1）；"
                    + "z 取 90% / 95% / 99% 三档；下界不小于 0",
                "",
                "样本不足 4 天的 SKU **不给预测**（不给点估计也不给区间），"
                    + "它进的是运行记录里的「跳过计数 + 原因」"),
            new AiForecastCapability(
                "18.4.2.2",
                "**关键影响因素**（因素名 + 方向 + 取值 + 口径说明）",
                true,
                "platform_forecast_factor 表：BASE_LEVEL / PROMOTION / SEASONALITY / PRICE_CHANGE / DATA_QUALITY；"
                    + "每条带 direction、value、impact 与 explanation",
                "",
                "样本不足时记「不适用」并写明为什么（如季节性需 14 天样本），"
                    + "**不假装算过了**"),
            new AiForecastCapability(
                "18.4.2.2",
                "促销 / 大促影响",
                true,
                "促销日历 platform_forecast_promotion（租户级 + SKU 范围 + 起止 + 折扣率 + 预期提升率）；"
                    + "预测期内生效的促销取**最大单次**提升率（不叠加）",
                "",
                "🔴 促销计划是**租户自己的业务事实**，每条带录入方式（MANUAL / IMPORT），"
                    + "**不假装是从上游同步来的**"),
            new AiForecastCapability(
                "18.4.2.2",
                "补货量建议（**只建议，不执行**）",
                true,
                "建议量 = max(0, 预测期需求**上界** − 在手库存 − 在途净额)；"
                    + "落 platform_forecast_suggestion 表，状态机 SUGGESTED / ADOPTED / REJECTED",
                "",
                "🔴 **本模块不建补货单、不改下游表**：库存 / 在途读不到时**不生成**这条建议，"
                    + "绝不拿 0 冒充「库存为 0」"),
            new AiForecastCapability(
                "18.4.2.2",
                "价格弹性与调价建议",
                true,
                "弹性 = 相邻两日「Δln(销量) / Δln(单价)」的**中位数**（需 ≥ 2 组样本对）；"
                    + "调价建议只在弹性 ≤ -0.5 时给出，幅度上限 10%",
                "",
                "🔴 弹性算不出来（没有价格样本 / 价格没变过 / 销量为 0）时**不给调价建议**，"
                    + "并如实报出原因；**没有「已改价」这个状态**"),
            new AiForecastCapability(
                "18.4.2.2",
                "爆品识别",
                true,
                "统计窗口（默认 30 天）销量 ≥ 100 件 **且** 相对上一个等长窗口增长率 ≥ 20%",
                "",
                "没有基期（上一个等长窗口没有记录）时**不算爆品**，并说明「没有基期」"),
            new AiForecastCapability(
                "18.4.2.2",
                "机会品发现",
                true,
                "近 7 天日均 ÷ 前 7 天日均 ≥ 1.3 且近期日均 ≥ 1 件",
                "",
                "趋势判定只看本模块自己的销量台账，不掺任何「模型判断」"),
            new AiForecastCapability(
                "18.4.2.2",
                "历史销量输入台账",
                true,
                "platform_forecast_demand_history：逐 SKU 逐日销量；"
                    + "source='MANUAL'（运营录入）/ 'REPORTING'（13 分析同步），每行带来源",
                "",
                "同一 SKU 同一天只允许一条 ⇒ 同步与录入撞键时**明确报冲突**，不重复计数"),
            new AiForecastCapability(
                "18.4.2.2",
                "跨服务取数（13 分析 / 6 采购 / 7 库存）",
                true,
                "ForecastSalesPort（reporting 8088 销量趋势）、WarehouseInventoryPort + ForecastInTransitPort"
                    + "（warehouse 8084 在手库存 / 在途净额）、ForecastPurchasePort（trade 8085 采购事实）",
                "",
                "⚠️ **连通性以最近一次运行为准**（运行记录上的 inventory_connected 等标记）："
                    + "读不到时该次运行**不生成**对应的建议，而不是拿 0 顶上"),
            new AiForecastCapability(
                "18.4.2.2",
                "**AI 大模型预测 / 自动改价 / 自动补货**",
                false,
                "本仓目前没有任何真实的 AI / LLM 供应商适配器"
                    + "（backend / services / tools 下不存在 openai / anthropic / qwen / deepseek 之类的客户端）",
                "18.4",
                "本模块**只产出建议**：结构上就没有「已执行」状态，"
                    + "补货单归 6 采购、调价归 5 定价，跨服务写它们的库是被禁止的"));

    var gaps =
        List.of(
            new AiForecastGap(
                "6.2.1.2",
                "补货建议执行（生成补货单）",
                "trade",
                false,
                "下游",
                "本模块只给**建议量**；补货单的创建归 6 采购，"
                    + "本模块**不写**它的表、**不**建跨库 FK、**不**建共享视图"),
            new AiForecastGap(
                "5.4",
                "调价执行（改价）",
                "catalog",
                false,
                "下游",
                "本模块只给**调价建议与弹性**；改价归 5 定价，"
                    + "建议采纳后由下游自己去取，本模块**不执行**"),
            new AiForecastGap(
                "18.4",
                "AI / LLM 供应商适配器",
                "platform",
                false,
                "卷级基础设施",
                "18.4 各模块共用的通道；本模块的预测用确定性算法，不依赖它"),
            new AiForecastGap(
                "18.4.3.2",
                "预测准确度回测",
                "platform",
                false,
                "下游",
                "回测口径归 18.4.3.2；本模块已把每次运行的算法、参数、区间与因素**完整落库**，"
                    + "回测拿得到原始输入"));

    var algorithms =
        List.of(
            new AiForecastAlgorithm(
                "MOVING_AVERAGE",
                "移动平均",
                "基线 = 统计窗口内**最近 maWindowDays 天**的日均销量；"
                    + "预测期总需求 = 基线 × 促销因子 × 预测天数",
                List.of(
                    new AiForecastParam(
                        "maWindowDays", "默认 3", "取最近几天的均值做基线（1 ~ 30）"),
                    new AiForecastParam(
                        "promoFactor", "1 + 最大单次促销提升率", "预测期内生效的促销取**最大**一个，不叠加"))),
            new AiForecastAlgorithm(
                "EXPONENTIAL_SMOOTHING",
                "指数平滑",
                "水平 level = α × 当日销量 + (1-α) × 上一期水平（首期用当日销量初始化）；"
                    + "基线 = 最后一期水平；预测期总需求 = 基线 × 促销因子 × 预测天数",
                List.of(
                    new AiForecastParam("alpha", "默认 0.3", "α 越大越看重近期（0 < α < 1）"),
                    new AiForecastParam(
                        "promoFactor", "1 + 最大单次促销提升率", "预测期内生效的促销取**最大**一个，不叠加"))));

    var rules =
        List.of(
            new AiForecastRuleNote(
                "SERIES",
                "统计窗口内的样本是**整条日历序列**：有记录的日子取登记销量，"
                    + "**没有记录的日子按 0 销量计**（台账登记的是逐日销量，没登记 = 当天没有销量）。"
                    + "⇒ 基线是日历日均，不是「有销量那几天的均值」。"),
            new AiForecastRuleNote(
                "SAMPLE",
                "样本天数（窗口内有记录的天数）少于 " + MIN_SAMPLE_DAYS + " 天的 SKU **不给预测**："
                    + "残差算不稳，区间会窄得没有意义。它进的是运行记录里的「跳过计数 + 原因」。"),
            new AiForecastRuleNote(
                "INTERVAL",
                "区间 = 点估计 ± z × σ × √预测天数：σ 是**一步预测误差**的样本标准差（分母 n-1），"
                    + "z 取 90% / 95% / 99% 三档。下界不小于 0（需求不能是负数）。"),
            new AiForecastRuleNote(
                "COVERAGE",
                "**样本覆盖度 = 有记录的天数 ÷ 窗口天数**。覆盖度低说明台账可能只登记了有销量的日子，"
                    + "基线会偏低 —— 这条会作为 DATA_QUALITY 因素显示在预测详情里，**不藏着**。"),
            new AiForecastRuleNote(
                "PROMOTION",
                "促销因子 = 1 + 预测期内生效促销的**最大**预期提升率（不叠加，避免高估）。"
                    + "预期提升率在登记促销时**必填** —— 没有它就说不清促销会把销量推高多少。"),
            new AiForecastRuleNote(
                "INVENTORY",
                "补货建议量 = max(0, 预测期需求**上界** − 在手库存 − 在途净额)。"
                    + "取上界是保守口径（宁可多备也别缺货）。"
                    + "⚠️ 在途只扣**一次**（7 库存的在途净额已含采购发运腿），"
                    + "6 采购的未到货量**只作参考**，不重复扣减。"),
            new AiForecastRuleNote(
                "ELASTICITY",
                "价格弹性 = 相邻两日「Δln(销量) / Δln(单价)」的**中位数**（中位数不被一两个异常日带偏），"
                    + "至少要 2 组样本对；销量为 0 或价格没变过的那几天**不参与**。"
                    + "弹性算不出来时**不给调价建议**，并报出原因。"),
            new AiForecastRuleNote(
                "ADVICE",
                "🔴 **建议只作为参考输入，不自动执行**：本模块的建议台账只有"
                    + " SUGGESTED / ADOPTED / REJECTED 三态，**没有「已执行」** —— "
                    + "补货单归 6 采购、调价归 5 定价，跨服务写它们的库是被禁止的。"));

    var thresholds =
        List.of(
            new AiForecastThreshold(
                "HOT_UNITS", "爆品窗口销量阈值", HOT_UNITS_THRESHOLD, "件",
                "统计窗口内销量达到这个数才算「有量」"),
            new AiForecastThreshold(
                "HOT_GROWTH", "爆品增长率阈值", HOT_GROWTH_THRESHOLD, "比例",
                "相对上一个等长窗口的增长率；没有基期时不算爆品"),
            new AiForecastThreshold(
                "OPPORTUNITY_TREND", "机会品趋势阈值", OPPORTUNITY_TREND_THRESHOLD, "倍",
                "近 7 天日均 ÷ 前 7 天日均"),
            new AiForecastThreshold(
                "OPPORTUNITY_MIN_DAILY", "机会品基线阈值", OPPORTUNITY_MIN_DAILY_UNITS, "件/天",
                "近期日均销量至少要到这个数"),
            new AiForecastThreshold(
                "PRICE_ELASTICITY", "给调价建议的弹性门槛", PRICE_ELASTICITY_THRESHOLD, "弹性",
                "弹性 ≤ 这个值（降价确实能带量）才给调价建议"),
            new AiForecastThreshold(
                "PRICE_ADJUST_CAP", "建议调价幅度上限", MAX_PRICE_ADJUST_RATE, "比例",
                "保守试探幅度，不是「一次调到最优」"),
            new AiForecastThreshold(
                "MIN_SAMPLE_DAYS", "样本天数下限", BigDecimal.valueOf(MIN_SAMPLE_DAYS), "天",
                "低于这个数不给预测，也不给区间"));

    var sources =
        List.of(
            new AiForecastSource(
                "REPORTING_SALES",
                "历史销量（商品维度销量，按 SKU 码分组）",
                "reporting（13 分析）",
                true,
                "GET /reporting/sales-trend?granularity=DAY&metricCodes=PRODUCT_SALES_QUANTITY",
                "",
                "⚠️ 上游的销量口径**不带价格**：单价在「恰好一个币种」时才由成交金额 ÷ 销量推出，"
                    + "多币种时单价为 null（**不编一个单价出来**）"),
            new AiForecastSource(
                "WAREHOUSE_ON_HAND",
                "在手库存",
                "warehouse（7 库存）",
                true,
                "GET /warehouse/inventory/overview",
                "",
                "读不到时该次运行**不生成补货建议**（不用 0 冒充库存为 0）"),
            new AiForecastSource(
                "WAREHOUSE_IN_TRANSIT",
                "在途净额（按目的仓 × SKU）",
                "warehouse（7 库存）",
                true,
                "GET /warehouse/inventory/in-transit/netting",
                "",
                "🔴 已含采购发运腿 ⇒ 补货建议**只扣一次**，6 采购的未到货量只作参考"),
            new AiForecastSource(
                "TRADE_PURCHASE",
                "采购侧未到货量（计划量 − 到货量，仅作参考）",
                "trade（6 采购）",
                true,
                "GET /trade/purchase-analytics/facts",
                "",
                "**不参与补货扣减**（与在途净额重复扣减会让建议量偏小）"),
            new AiForecastSource(
                "AI_MODEL",
                "AI / LLM 模型通道",
                "platform（18.4 卷级）",
                false,
                "（无）",
                "18.4",
                "本仓没有任何真实的 AI / LLM 供应商适配器 ⇒ 预测走确定性算法，"
                    + "**不假装**有模型在跑"));

    return new AiForecastCapabilities(capabilities, gaps, algorithms, rules, thresholds, sources);
  }

  /** 概览：几个数 + **最近一次运行的上游连通性**（真实读的结果）。 */
  public AiForecastOverview overview() {
    var lastRun =
        sql.fetchOne(
            "select status, inventory_connected, in_transit_connected, purchase_connected,"
                + " started_at from platform_forecast_run order by started_at desc limit 1");
    var historySkus =
        count("select count(distinct sku_code) from platform_forecast_demand_history");
    return new AiForecastOverview(
        count("select count(*) from platform_forecast_run"),
        count("select count(*) from platform_forecast_result"),
        count("select count(*) from platform_forecast_suggestion"),
        count("select count(*) from platform_forecast_suggestion where status = 'ADOPTED'"),
        count("select count(*) from platform_forecast_suggestion where status = 'REJECTED'"),
        count("select count(*) from platform_forecast_promotion"),
        count("select count(*) from platform_forecast_demand_history"),
        historySkus,
        lastRun == null
            ? OffsetDateTime.now(ZoneOffset.UTC)
            : lastRun.get("started_at", OffsetDateTime.class));
  }

  // ================================================================ 促销日历

  /** 促销日历列表（真分页 + 真筛选）。 */
  public AiForecastPromotionPage promotions(
      String keyword, String promotionType, String scopeType, Boolean activeOnly, int page, int size) {
    var where = new StringBuilder(" where 1 = 1");
    var params = new ArrayList<Object>();
    if (keyword != null && !keyword.isBlank()) {
      where.append(" and (name ilike ? or scope_value ilike ?)");
      params.add("%" + keyword.trim() + "%");
      params.add("%" + keyword.trim() + "%");
    }
    if (promotionType != null && !promotionType.isBlank()) {
      where.append(" and promotion_type = ?");
      params.add(promotionType.trim().toUpperCase(Locale.ROOT));
    }
    if (scopeType != null && !scopeType.isBlank()) {
      where.append(" and scope_type = ?");
      params.add(scopeType.trim().toUpperCase(Locale.ROOT));
    }
    if (activeOnly != null && activeOnly) {
      where.append(" and starts_at <= ?::timestamptz and ends_at >= ?::timestamptz");
      var now = OffsetDateTime.now(ZoneOffset.UTC);
      params.add(now);
      params.add(now);
    }
    var total =
        count(
            "select count(*) from platform_forecast_promotion" + where,
            params.toArray());
    var current = Math.max(page, 0);
    var limit = pageSize(size);
    var rows =
        sql.fetch(
            "select id,name,promotion_type,scope_type,scope_value,starts_at,ends_at,discount_rate,"
                + " expected_lift,source,source_ref,note,created_at,updated_at"
                + " from platform_forecast_promotion"
                + where
                + " order by starts_at desc, name limit ? offset ?",
            concat(params.toArray(), limit, (long) current * limit));
    return new AiForecastPromotionPage(
        rows.map(AiForecastService::toPromotionRow), current, limit, total, pages(total, limit));
  }

  /** 新增或修改一条促销。 */
  public AiForecastPromotionRow savePromotion(
      AiForecastPromotionInput input, AccessService.Access actor) {
    var name = required(input.name(), "促销名称", 120);
    var type = required(input.promotionType(), "促销类型", 16).toUpperCase(Locale.ROOT);
    check(
        List.of("PROMO", "BIG_SALE", "FLASH", "SEASONAL", "CLEARANCE").contains(type),
        "促销类型只能是 PROMO / BIG_SALE / FLASH / SEASONAL / CLEARANCE");
    var scopeType =
        input.scopeType() == null || input.scopeType().isBlank()
            ? "ALL"
            : input.scopeType().trim().toUpperCase(Locale.ROOT);
    check(scopeType.equals("ALL") || scopeType.equals("SKU"), "适用范围只能是 ALL（租户级）或 SKU");
    var scopeValue = optional(input.scopeValue(), "SKU 码", 64);
    if (scopeType.equals("SKU")) {
      check(!scopeValue.isBlank(), "适用范围是 SKU 时必须给出 SKU 码");
    } else {
      scopeValue = "";
    }
    check(input.startsAt() != null, "促销开始时间不能为空");
    check(input.endsAt() != null, "促销结束时间不能为空");
    check(input.endsAt().isAfter(input.startsAt()), "促销结束时间必须晚于开始时间");
    check(input.expectedLift() != null, "预期销量提升率不能为空 —— 没有它预测无法计入促销影响");
    check(
        input.expectedLift().compareTo(BigDecimal.ONE.negate()) > 0,
        "预期销量提升率必须大于 -100%（销量不可能归零以下）");
    if (input.discountRate() != null) {
      check(
          input.discountRate().signum() > 0 && input.discountRate().compareTo(BigDecimal.ONE) <= 0,
          "折扣率必须大于 0 且不超过 1（0.2 = 降价 20%）");
    }
    var source =
        input.source() == null || input.source().isBlank()
            ? "MANUAL"
            : input.source().trim().toUpperCase(Locale.ROOT);
    check(source.equals("MANUAL") || source.equals("IMPORT"), "录入方式只能是 MANUAL 或 IMPORT");
    var sourceRef = optional(input.sourceRef(), "来源单号", 200);
    var note = optional(input.note(), "备注", 500);

    var existing =
        sql.fetchOne(
            "select id from platform_forecast_promotion where name = ? and scope_type = ?"
                + " and scope_value = ? and starts_at = ?::timestamptz",
            name,
            scopeType,
            scopeValue,
            input.startsAt());
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    var id = existing == null ? UUID.randomUUID() : existing.get("id", UUID.class);
    if (existing == null) {
      sql.execute(
          "insert into platform_forecast_promotion(id,tenant_id,name,promotion_type,scope_type,"
              + " scope_value,starts_at,ends_at,discount_rate,expected_lift,source,source_ref,note,"
              + " created_by,created_at,updated_at)"
              + " values(?,?,?,?,?,?,?::timestamptz,?::timestamptz,?,?,?,?,?,?,?::timestamptz,?::timestamptz)",
          id,
          currentTenant(),
          name,
          type,
          scopeType,
          scopeValue,
          input.startsAt(),
          input.endsAt(),
          input.discountRate(),
          input.expectedLift(),
          source,
          sourceRef,
          note,
          actor.userId(),
          now,
          now);
    } else {
      sql.execute(
          "update platform_forecast_promotion set promotion_type = ?, ends_at = ?::timestamptz,"
              + " discount_rate = ?, expected_lift = ?, source = ?, source_ref = ?, note = ?,"
              + " updated_at = ?::timestamptz where id = ?",
          type,
          input.endsAt(),
          input.discountRate(),
          input.expectedLift(),
          source,
          sourceRef,
          note,
          now,
          id);
    }
    return promotionRow(id);
  }

  /** 删除一条促销。 */
  public void deletePromotion(UUID id) {
    var row = sql.fetchOne("select name from platform_forecast_promotion where id = ?", id);
    check(row != null, "促销不存在");
    sql.execute("delete from platform_forecast_promotion where id = ?", id);
  }

  private AiForecastPromotionRow promotionRow(UUID id) {
    return sql.fetchOne(
            "select id,name,promotion_type,scope_type,scope_value,starts_at,ends_at,discount_rate,"
                + " expected_lift,source,source_ref,note,created_at,updated_at"
                + " from platform_forecast_promotion where id = ?",
            id)
        .map(AiForecastService::toPromotionRow);
  }

  private static AiForecastPromotionRow toPromotionRow(org.jooq.Record r) {
    var type = r.get("promotion_type", String.class);
    return new AiForecastPromotionRow(
        r.get("id", UUID.class),
        r.get("name", String.class),
        type,
        promotionTypeLabel(type),
        r.get("scope_type", String.class),
        r.get("scope_value", String.class),
        r.get("starts_at", OffsetDateTime.class),
        r.get("ends_at", OffsetDateTime.class),
        r.get("discount_rate", BigDecimal.class),
        r.get("expected_lift", BigDecimal.class),
        r.get("source", String.class),
        r.get("source_ref", String.class),
        r.get("note", String.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  private static String promotionTypeLabel(String type) {
    if (type == null) return "";
    return switch (type) {
      case "PROMO" -> "促销";
      case "BIG_SALE" -> "大促";
      case "FLASH" -> "秒杀";
      case "SEASONAL" -> "季节性活动";
      case "CLEARANCE" -> "清仓";
      default -> type;
    };
  }

  // ================================================================ 需求历史

  /** 需求历史列表（真分页 + 真筛选）。 */
  public AiForecastHistoryPage history(
      String skuCode, LocalDate from, LocalDate to, String source, int page, int size) {
    var where = new StringBuilder(" where 1 = 1");
    var params = new ArrayList<Object>();
    if (skuCode != null && !skuCode.isBlank()) {
      where.append(" and sku_code = ?");
      params.add(skuCode.trim());
    }
    if (from != null) {
      where.append(" and business_date >= ?::date");
      params.add(from);
    }
    if (to != null) {
      where.append(" and business_date <= ?::date");
      params.add(to);
    }
    if (source != null && !source.isBlank()) {
      where.append(" and source = ?");
      params.add(source.trim().toUpperCase(Locale.ROOT));
    }
    var total =
        count("select count(*) from platform_forecast_demand_history" + where, params.toArray());
    var current = Math.max(page, 0);
    var limit = pageSize(size);
    var rows =
        sql.fetch(
            "select id,sku_code,sku_name,business_date,units,unit_price,revenue,currency,channel_code,"
                + " source,source_ref,recorded_at from platform_forecast_demand_history"
                + where
                + " order by business_date desc, sku_code limit ? offset ?",
            concat(params.toArray(), limit, (long) current * limit));
    return new AiForecastHistoryPage(
        rows.map(AiForecastService::toHistoryRow), current, limit, total, pages(total, limit));
  }

  /** 录入一条需求历史（**人工录入**；台账里 source='MANUAL'）。 */
  public AiForecastHistoryRow saveHistory(AiForecastHistoryInput input) {
    var skuCode = required(input.skuCode(), "SKU 码", 64);
    var skuName = optional(input.skuName(), "SKU 名称", 200);
    check(input.businessDate() != null, "业务日期不能为空");
    check(input.units() != null, "销量不能为空");
    check(input.units().signum() >= 0, "销量不能为负");
    var unitPrice = input.unitPrice();
    var revenue = input.revenue();
    check(unitPrice == null || unitPrice.signum() >= 0, "单价不能为负");
    check(revenue == null || revenue.signum() >= 0, "销售额不能为负");
    var currency = optional(input.currency(), "币种", 3).toUpperCase(Locale.ROOT);
    if (unitPrice != null || revenue != null) {
      check(currency.matches("^[A-Z]{3}$"), "有单价或销售额时必须给出三位大写币种");
    }
    var channelCode = optional(input.channelCode(), "渠道编码", 64);
    var sourceRef = optional(input.sourceRef(), "来源单号", 200);
    optional(input.note(), "备注", 500);

    var existing =
        sql.fetchOne(
            "select id from platform_forecast_demand_history where sku_code = ? and business_date = ?::date",
            skuCode,
            input.businessDate());
    if (existing != null) {
      conflict(
          "该 SKU 在 "
              + input.businessDate()
              + " 已经有需求历史记录了（同一 SKU 同一天只允许一条，避免重复计数）；"
              + "如需更正请先删除那一条。");
    }
    var id = UUID.randomUUID();
    sql.execute(
        "insert into platform_forecast_demand_history(id,tenant_id,sku_code,sku_name,business_date,"
            + " units,unit_price,revenue,currency,channel_code,source,source_ref,recorded_at)"
            + " values(?,?,?,?,?::date,?,?,?,?,?,'MANUAL',?,?::timestamptz)",
        id,
        currentTenant(),
        skuCode,
        skuName,
        input.businessDate(),
        input.units(),
        unitPrice,
        revenue,
        currency.isBlank() ? null : currency,
        channelCode,
        sourceRef,
        OffsetDateTime.now(ZoneOffset.UTC));
    return historyRow(id);
  }

  /** 删除一条需求历史（更正录入用）。 */
  public void deleteHistory(UUID id) {
    var row = sql.fetchOne("select sku_code from platform_forecast_demand_history where id = ?", id);
    check(row != null, "需求历史记录不存在");
    sql.execute("delete from platform_forecast_demand_history where id = ?", id);
  }

  private AiForecastHistoryRow historyRow(UUID id) {
    return sql.fetchOne(
            "select id,sku_code,sku_name,business_date,units,unit_price,revenue,currency,channel_code,"
                + " source,source_ref,recorded_at from platform_forecast_demand_history where id = ?",
            id)
        .map(AiForecastService::toHistoryRow);
  }

  private static AiForecastHistoryRow toHistoryRow(org.jooq.Record r) {
    return new AiForecastHistoryRow(
        r.get("id", UUID.class),
        r.get("sku_code", String.class),
        r.get("sku_name", String.class),
        r.get("business_date", LocalDate.class),
        r.get("units", BigDecimal.class),
        r.get("unit_price", BigDecimal.class),
        r.get("revenue", BigDecimal.class),
        r.get("currency", String.class),
        r.get("channel_code", String.class),
        r.get("source", String.class),
        r.get("source_ref", String.class),
        r.get("recorded_at", OffsetDateTime.class));
  }

  /**
   * 从 **13 分析**同步历史销量。
   *
   * <p>🔴 **上游读不到就一个字都不写**：端口读不到会翻成 503，本方法直接把异常原样抛出，
   * 台账**保持原样** —— 一个「同步失败但看起来成功」的返回会让人以为数据已经更新了。
   *
   * <p>🔴 **撞键就跳过并计数**：同一个 SKU 同一天已有一条（人工录入的）时，
   * **不覆盖、不累加** —— 重复计数会直接把基线抬高。
   */
  public AiForecastHistorySyncResult syncHistory(LocalDate from, LocalDate to, String bearer) {
    var start = from == null ? LocalDate.now(ZoneOffset.UTC).minusDays(DEFAULT_WINDOW_DAYS - 1) : from;
    var end = to == null ? LocalDate.now(ZoneOffset.UTC) : to;
    check(!start.isAfter(end), "同步起始日期不能晚于结束日期");
    check(
        start.plusDays(365).isAfter(end),
        "同步窗口最多 365 天（当前 " + (end.toEpochDay() - start.toEpochDay() + 1) + " 天）");

    var upstream = sales.salesBySku(start, end, bearer);
    var written = 0;
    var skipped = 0;
    for (var row : upstream) {
      var exists =
          sql.fetchOne(
                  "select id from platform_forecast_demand_history where sku_code = ?"
                      + " and business_date = ?::date",
                  row.skuCode(),
                  row.businessDate())
              != null;
      if (exists) {
        skipped++;
        continue;
      }
      sql.execute(
          "insert into platform_forecast_demand_history(id,tenant_id,sku_code,sku_name,business_date,"
              + " units,unit_price,revenue,currency,channel_code,source,source_ref,recorded_at)"
              + " values(?,?,?,?,?::date,?,?,?,?,'','REPORTING',?,?::timestamptz)",
          UUID.randomUUID(),
          currentTenant(),
          row.skuCode(),
          row.skuName(),
          row.businessDate(),
          row.units(),
          row.unitPrice(),
          row.revenue(),
          row.currency(),
          "reporting/sales-trend#PRODUCT_SALES_QUANTITY"
              + (row.unitPrice() == null ? "" : ";price=PRODUCT_SALES_AMOUNT/" + row.currency()),
          OffsetDateTime.now(ZoneOffset.UTC));
      written++;
    }
    var note =
        written == 0 && upstream.isEmpty()
            ? "上游窗口内**没有**销量事实（不是读不到 —— 读不到会直接报 503 且不写任何数据）"
            : "已写入 " + written + " 条；跳过 " + skipped + " 条（当天已有记录，不覆盖、不重复计数）";
    return new AiForecastHistorySyncResult(
        true,
        "REPORTING",
        "GET /reporting/sales-trend",
        start,
        end,
        written,
        skipped,
        upstream.size(),
        note,
        "");
  }

  // ================================================================ 预测运行

  /** 运行列表（真分页 + 真筛选）。 */
  public AiForecastRunPage runs(String status, int page, int size) {
    var where = new StringBuilder(" where 1 = 1");
    var params = new ArrayList<Object>();
    if (status != null && !status.isBlank()) {
      where.append(" and status = ?");
      params.add(status.trim().toUpperCase(Locale.ROOT));
    }
    var total = count("select count(*) from platform_forecast_run" + where, params.toArray());
    var current = Math.max(page, 0);
    var limit = pageSize(size);
    var rows =
        sql.fetch(
            "select id,run_no,algorithm,ma_window_days,smoothing_alpha,window_days,horizon_days,"
                + " confidence_level,z_value,history_source,status,evaluated_sku_count,"
                + " forecast_sku_count,skipped_sku_count,skipped_reason,inventory_connected,"
                + " in_transit_connected,purchase_connected,note,started_at,finished_at"
                + " from platform_forecast_run"
                + where
                + " order by started_at desc limit ? offset ?",
            concat(params.toArray(), limit, (long) current * limit));
    return new AiForecastRunPage(
        rows.map(AiForecastService::toRunRow), current, limit, total, pages(total, limit));
  }

  /** 运行详情（运行 + 每个 SKU 的结果 + 影响因素）。 */
  public AiForecastRunDetail runDetail(UUID id) {
    var run =
        sql.fetchOne(
                "select id,run_no,algorithm,ma_window_days,smoothing_alpha,window_days,horizon_days,"
                    + " confidence_level,z_value,history_source,status,evaluated_sku_count,"
                    + " forecast_sku_count,skipped_sku_count,skipped_reason,inventory_connected,"
                    + " in_transit_connected,purchase_connected,note,started_at,finished_at"
                    + " from platform_forecast_run where id = ?",
                id)
            .map(AiForecastService::toRunRow);
    check(run != null, "预测运行不存在");
    var results =
        sql.fetch(
                "select id,run_id,sku_code,sku_name,algorithm,ma_window_days,smoothing_alpha,"
                    + " window_days,horizon_days,history_source,sample_days,coverage_rate,"
                    + " baseline_daily,promo_factor,point,lower,upper,sigma,z_value,confidence_level,"
                    + " inventory_qty,in_transit_qty,purchase_pending_qty,unit_price,currency,created_at"
                    + " from platform_forecast_result where run_id = ? order by sku_code",
                id)
            .map(AiForecastService::toResultRow);
    var details = new ArrayList<AiForecastResultDetail>();
    for (var result : results) {
      details.add(new AiForecastResultDetail(result, factorsOf(result.id())));
    }
    return new AiForecastRunDetail(run, details);
  }

  /** 预测结果列表（真分页 + 真筛选）。 */
  public AiForecastResultPage forecasts(
      String skuCode, UUID runId, String historySource, int page, int size) {
    var where = new StringBuilder(" where 1 = 1");
    var params = new ArrayList<Object>();
    if (skuCode != null && !skuCode.isBlank()) {
      where.append(" and sku_code = ?");
      params.add(skuCode.trim());
    }
    if (runId != null) {
      where.append(" and run_id = ?");
      params.add(runId);
    }
    if (historySource != null && !historySource.isBlank()) {
      where.append(" and history_source = ?");
      params.add(historySource.trim().toUpperCase(Locale.ROOT));
    }
    var total = count("select count(*) from platform_forecast_result" + where, params.toArray());
    var current = Math.max(page, 0);
    var limit = pageSize(size);
    var rows =
        sql.fetch(
            "select id,run_id,sku_code,sku_name,algorithm,ma_window_days,smoothing_alpha,window_days,"
                + " horizon_days,history_source,sample_days,coverage_rate,baseline_daily,promo_factor,"
                + " point,lower,upper,sigma,z_value,confidence_level,inventory_qty,in_transit_qty,"
                + " purchase_pending_qty,unit_price,currency,created_at from platform_forecast_result"
                + where
                + " order by created_at desc, sku_code limit ? offset ?",
            concat(params.toArray(), limit, (long) current * limit));
    return new AiForecastResultPage(
        rows.map(AiForecastService::toResultRow), current, limit, total, pages(total, limit));
  }

  /** 预测详情（结果 + 影响因素）。 */
  public AiForecastResultDetail forecastDetail(UUID id) {
    var result =
        sql.fetchOne(
                "select id,run_id,sku_code,sku_name,algorithm,ma_window_days,smoothing_alpha,"
                    + " window_days,horizon_days,history_source,sample_days,coverage_rate,"
                    + " baseline_daily,promo_factor,point,lower,upper,sigma,z_value,confidence_level,"
                    + " inventory_qty,in_transit_qty,purchase_pending_qty,unit_price,currency,created_at"
                    + " from platform_forecast_result where id = ?",
                id)
            .map(AiForecastService::toResultRow);
    check(result != null, "预测结果不存在");
    return new AiForecastResultDetail(result, factorsOf(id));
  }

  private List<AiForecastFactorRow> factorsOf(UUID resultId) {
    return sql.fetch(
            "select id,result_id,factor_code,factor_label,direction,value,value_text,impact,"
                + " explanation,source,created_at from platform_forecast_factor where result_id = ?"
                + " order by factor_code",
            resultId)
        .map(
            r ->
                new AiForecastFactorRow(
                    r.get("id", UUID.class),
                    r.get("result_id", UUID.class),
                    r.get("factor_code", String.class),
                    r.get("factor_label", String.class),
                    r.get("direction", String.class),
                    r.get("value", BigDecimal.class),
                    r.get("value_text", String.class),
                    r.get("impact", BigDecimal.class),
                    r.get("explanation", String.class),
                    r.get("source", String.class),
                    r.get("created_at", OffsetDateTime.class)));
  }

  // ================================================================ 建议

  /** 建议列表（真分页 + 真筛选）。 */
  public AiForecastSuggestionPage suggestions(
      String suggestionType, String status, String skuCode, int page, int size) {
    var where = new StringBuilder(" where 1 = 1");
    var params = new ArrayList<Object>();
    if (suggestionType != null && !suggestionType.isBlank()) {
      where.append(" and suggestion_type = ?");
      params.add(suggestionType.trim().toUpperCase(Locale.ROOT));
    }
    if (status != null && !status.isBlank()) {
      where.append(" and status = ?");
      params.add(status.trim().toUpperCase(Locale.ROOT));
    }
    if (skuCode != null && !skuCode.isBlank()) {
      where.append(" and sku_code = ?");
      params.add(skuCode.trim());
    }
    var total = count("select count(*) from platform_forecast_suggestion" + where, params.toArray());
    var current = Math.max(page, 0);
    var limit = pageSize(size);
    var rows =
        sql.fetch(
            "select id,suggestion_type,run_id,result_id,sku_code,sku_name,current_qty,in_transit_qty,"
                + " purchase_pending_qty,suggested_qty,demand_point,demand_lower,demand_upper,"
                + " current_price,suggested_price,price_delta_rate,expected_units_delta_rate,"
                + " elasticity,currency,status,basis,decided_by,decided_at,decision_note,created_at,"
                + " updated_at from platform_forecast_suggestion"
                + where
                + " order by created_at desc, sku_code limit ? offset ?",
            concat(params.toArray(), limit, (long) current * limit));
    return new AiForecastSuggestionPage(
        rows.map(AiForecastService::toSuggestionRow), current, limit, total, pages(total, limit));
  }

  /**
   * 人工**采纳**一条建议。
   *
   * <p>🔴 **只是记下「人决定了采纳」**：本模块**不执行**任何下游动作
   * （不建补货单、不改价）。这是蓝图加粗句「建议只作为参考输入，不自动执行」的落点。
   */
  public AiForecastSuggestionRow adoptSuggestion(UUID id, String note, AccessService.Access actor) {
    return decideSuggestion(id, "ADOPTED", note, actor);
  }

  /** 人工**驳回**一条建议。 */
  public AiForecastSuggestionRow rejectSuggestion(UUID id, String note, AccessService.Access actor) {
    return decideSuggestion(id, "REJECTED", note, actor);
  }

  private AiForecastSuggestionRow decideSuggestion(
      UUID id, String status, String note, AccessService.Access actor) {
    var row =
        sql.fetchOne("select status from platform_forecast_suggestion where id = ?", id);
    check(row != null, "建议不存在");
    var current = row.get("status", String.class);
    if (!"SUGGESTED".equals(current)) {
      conflict("这条建议已经" + ("ADOPTED".equals(current) ? "被采纳" : "被驳回") + "了，不能重复决策");
    }
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    sql.execute(
        "update platform_forecast_suggestion set status = ?, decided_by = ?, decided_at = ?::timestamptz,"
            + " decision_note = ?, updated_at = ?::timestamptz where id = ?",
        status,
        actor.userId(),
        now,
        optional(note, "决策说明", 500),
        now,
        id);
    return suggestionRow(id);
  }

  private AiForecastSuggestionRow suggestionRow(UUID id) {
    return sql.fetchOne(
            "select id,suggestion_type,run_id,result_id,sku_code,sku_name,current_qty,in_transit_qty,"
                + " purchase_pending_qty,suggested_qty,demand_point,demand_lower,demand_upper,"
                + " current_price,suggested_price,price_delta_rate,expected_units_delta_rate,"
                + " elasticity,currency,status,basis,decided_by,decided_at,decision_note,created_at,"
                + " updated_at from platform_forecast_suggestion where id = ?",
            id)
        .map(AiForecastService::toSuggestionRow);
  }

  private static AiForecastSuggestionRow toSuggestionRow(org.jooq.Record r) {
    return new AiForecastSuggestionRow(
        r.get("id", UUID.class),
        r.get("suggestion_type", String.class),
        r.get("run_id", UUID.class),
        r.get("result_id", UUID.class),
        r.get("sku_code", String.class),
        r.get("sku_name", String.class),
        r.get("current_qty", BigDecimal.class),
        r.get("in_transit_qty", BigDecimal.class),
        r.get("purchase_pending_qty", BigDecimal.class),
        r.get("suggested_qty", BigDecimal.class),
        r.get("demand_point", BigDecimal.class),
        r.get("demand_lower", BigDecimal.class),
        r.get("demand_upper", BigDecimal.class),
        r.get("current_price", BigDecimal.class),
        r.get("suggested_price", BigDecimal.class),
        r.get("price_delta_rate", BigDecimal.class),
        r.get("expected_units_delta_rate", BigDecimal.class),
        r.get("elasticity", BigDecimal.class),
        r.get("currency", String.class),
        r.get("status", String.class),
        r.get("basis", String.class),
        r.get("decided_by", UUID.class),
        r.get("decided_at", OffsetDateTime.class),
        r.get("decision_note", String.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  // ================================================================ 价格弹性

  /** 价格弹性（**算不出来的 SKU 也在列表里，并写明为什么**）。 */
  public AiForecastPriceElasticityView priceElasticity(String skuCode, int windowDays) {
    var window = Math.min(Math.max(windowDays <= 0 ? DEFAULT_WINDOW_DAYS : windowDays, 7), 365);
    var today = LocalDate.now(ZoneOffset.UTC);
    var from = today.minusDays(window - 1);
    var where = new StringBuilder(" where 1 = 1");
    var params = new ArrayList<Object>();
    if (skuCode != null && !skuCode.isBlank()) {
      where.append(" and sku_code = ?");
      params.add(skuCode.trim());
    }
    var skus =
        sql.fetch(
            "select sku_code, max(sku_name) as sku_name from platform_forecast_demand_history"
                + where
                + " and business_date >= ?::date and business_date <= ?::date"
                + " group by sku_code order by sku_code",
            concat(params.toArray(), from, today));
    var items = new ArrayList<AiForecastElasticityRow>();
    for (var sku : skus) {
      var code = sku.get("sku_code", String.class);
      var name = sku.get("sku_name", String.class);
      var points = historySeries(code, from, today);
      var estimate = estimateElasticity(points);
      var currentPrice = latestPrice(points);
      items.add(
          new AiForecastElasticityRow(
              code,
              name,
              estimate.available(),
              estimate.elasticity(),
              estimate.pairs(),
              window,
              currentPrice,
              currentPrice == null ? null : latestCurrency(points),
              "相邻两日 Δln(销量) / Δln(单价) 的中位数",
              estimate.reason(),
              estimate.basis()));
    }
    return new AiForecastPriceElasticityView(
        items,
        "相邻两日 Δln(销量) / Δln(单价) 的中位数（至少 2 组样本对；销量为 0 或价格未变的天不参与）",
        window,
        PRICE_ELASTICITY_THRESHOLD,
        "🔴 **弹性算不出来时不给调价建议**：本页会写明每个 SKU 差在哪"
            + "（没有价格样本 / 价格没变过 / 销量为 0）。");
  }

  // ================================================================ 爆品

  /** 爆品识别（**阈值 + 统计窗口写在这里，判定不是黑盒**）。 */
  public AiForecastHotItemView hotItems(int windowDays, int limit) {
    var window = Math.min(Math.max(windowDays <= 0 ? DEFAULT_WINDOW_DAYS : windowDays, 7), 365);
    var today = LocalDate.now(ZoneOffset.UTC);
    var currentFrom = today.minusDays(window - 1);
    var previousFrom = currentFrom.minusDays(window);
    var previousTo = currentFrom.minusDays(1);

    var current = windowUnits(currentFrom, today);
    var previous = windowUnits(previousFrom, previousTo);
    var skus = new java.util.TreeSet<String>();
    skus.addAll(current.keySet());
    skus.addAll(previous.keySet());

    var items = new ArrayList<AiForecastHotItemRow>();
    for (var code : skus) {
      var units = current.getOrDefault(code, BigDecimal.ZERO);
      var previousUnits = previous.get(code);
      var growth =
          previousUnits == null || previousUnits.signum() == 0
              ? null
              : units.subtract(previousUnits).divide(previousUnits, 6, RoundingMode.HALF_UP);
      var activeDays =
          sql.fetchOne(
                  "select count(*) from platform_forecast_demand_history where sku_code = ?"
                      + " and business_date >= ?::date and business_date <= ?::date and units > 0",
                  code,
                  currentFrom,
                  today)
              .get(0, Long.class);
      var name =
          sql.fetchOne(
                  "select sku_name from platform_forecast_demand_history where sku_code = ?"
                      + " order by business_date desc limit 1",
                  code)
              .get(0, String.class);
      boolean detected =
          units.compareTo(HOT_UNITS_THRESHOLD) >= 0
              && growth != null
              && growth.compareTo(HOT_GROWTH_THRESHOLD) >= 0;
      String reason;
      if (units.compareTo(HOT_UNITS_THRESHOLD) < 0) {
        reason = "窗口销量 " + units.stripTrailingZeros().toPlainString() + " 件，未达阈值 "
            + HOT_UNITS_THRESHOLD.stripTrailingZeros().toPlainString() + " 件";
      } else if (growth == null) {
        reason = "没有基期（上一个等长窗口没有记录），算不出增长率";
      } else if (growth.compareTo(HOT_GROWTH_THRESHOLD) < 0) {
        reason = "增长率 " + percent(growth) + " 未达阈值 " + percent(HOT_GROWTH_THRESHOLD);
      } else {
        reason = "窗口销量与增长率都达标";
      }
      items.add(
          new AiForecastHotItemRow(
              code,
              name == null ? "" : name,
              detected,
              units,
              previousUnits,
              growth,
              window,
              activeDays.intValue(),
              HOT_UNITS_THRESHOLD,
              HOT_GROWTH_THRESHOLD,
              reason,
              "判定口径：窗口 " + window + " 天销量 ≥ " + HOT_UNITS_THRESHOLD.stripTrailingZeros().toPlainString()
                  + " 件 **且** 相对上一个等长窗口（" + previousFrom + " ~ " + previousTo
                  + "）增长率 ≥ " + percent(HOT_GROWTH_THRESHOLD)));
    }
    items.sort(
        Comparator.comparing(AiForecastHotItemRow::detected)
            .reversed()
            .thenComparing(AiForecastHotItemRow::windowUnits, Comparator.reverseOrder()));
    var max = Math.min(Math.max(limit <= 0 ? 20 : limit, 1), MAX_PAGE_SIZE);
    return new AiForecastHotItemView(
        items.subList(0, Math.min(items.size(), max)),
        window,
        HOT_UNITS_THRESHOLD,
        HOT_GROWTH_THRESHOLD,
        "🔴 爆品 = 窗口销量达标 **且** 相对上一个等长窗口增长率达标；"
            + "**没有基期就不算爆品**（算不出增长率与「增长率是 0」是两件事）。");
  }

  // ================================================================ 机会品

  /** 机会品发现（**阈值 + 统计窗口写在这里**）。 */
  public AiForecastOpportunityView opportunities(int windowDays, int limit) {
    var window = Math.min(Math.max(windowDays <= 0 ? DEFAULT_WINDOW_DAYS : windowDays, 14), 365);
    var today = LocalDate.now(ZoneOffset.UTC);
    var recentFrom = today.minusDays(RECENT_WINDOW_DAYS - 1);
    var previousFrom = recentFrom.minusDays(RECENT_WINDOW_DAYS);
    var previousTo = recentFrom.minusDays(1);

    var skus =
        sql.fetch(
            "select distinct sku_code from platform_forecast_demand_history"
                + " where business_date >= ?::date and business_date <= ?::date",
            previousFrom,
            today);
    var items = new ArrayList<AiForecastOpportunityRow>();
    for (var row : skus) {
      var code = row.get("sku_code", String.class);
      var points = historySeries(code, previousFrom, today);
      var recentDaily = averageDaily(points, recentFrom, today);
      var previousDaily = averageDaily(points, previousFrom, previousTo);
      var trendRatio =
          previousDaily == null || previousDaily.signum() == 0 || recentDaily == null
              ? null
              : recentDaily.divide(previousDaily, 6, RoundingMode.HALF_UP);
      var name = points.isEmpty() ? "" : points.get(points.size() - 1).name();
      var latest = latestResult(code);
      boolean detected =
          trendRatio != null
              && trendRatio.compareTo(OPPORTUNITY_TREND_THRESHOLD) >= 0
              && recentDaily != null
              && recentDaily.compareTo(OPPORTUNITY_MIN_DAILY_UNITS) >= 0;
      String reason;
      if (recentDaily == null || previousDaily == null) {
        reason = "两个子窗口里有一个没有销量记录，算不出趋势";
      } else if (previousDaily.signum() == 0) {
        reason = "前 7 天日均为 0，算不出趋势比（不是「增长 0%」）";
      } else if (trendRatio != null && trendRatio.compareTo(OPPORTUNITY_TREND_THRESHOLD) < 0) {
        reason = "趋势比 " + trendRatio.stripTrailingZeros().toPlainString() + " 未达阈值 "
            + OPPORTUNITY_TREND_THRESHOLD.stripTrailingZeros().toPlainString();
      } else if (recentDaily.compareTo(OPPORTUNITY_MIN_DAILY_UNITS) < 0) {
        reason = "近期日均 " + recentDaily.stripTrailingZeros().toPlainString()
            + " 件未达基线阈值 " + OPPORTUNITY_MIN_DAILY_UNITS.stripTrailingZeros().toPlainString();
      } else {
        reason = "动销明显上升且基线达标";
      }
      var inventoryQty = latest == null ? null : latest.inventoryQty();
      var coverageDays =
          latest == null || inventoryQty == null || latest.dailyPoint() == null
                  || latest.dailyPoint().signum() == 0
              ? null
              : inventoryQty.divide(latest.dailyPoint(), 6, RoundingMode.HALF_UP);
      var gapQty =
          latest == null || latest.upper() == null
              ? null
              : latest.upper().subtract(num(inventoryQty)).max(BigDecimal.ZERO);
      items.add(
          new AiForecastOpportunityRow(
              code,
              name,
              detected,
              recentDaily,
              previousDaily,
              trendRatio,
              latest == null ? null : latest.point(),
              latest == null ? null : latest.upper(),
              inventoryQty,
              coverageDays,
              gapQty,
              window,
              OPPORTUNITY_TREND_THRESHOLD,
              OPPORTUNITY_MIN_DAILY_UNITS,
              reason,
              "判定口径：近 " + RECENT_WINDOW_DAYS + " 天日均 ÷ 前 " + RECENT_WINDOW_DAYS
                  + " 天日均 ≥ " + OPPORTUNITY_TREND_THRESHOLD.stripTrailingZeros().toPlainString()
                  + " **且** 近期日均 ≥ " + OPPORTUNITY_MIN_DAILY_UNITS.stripTrailingZeros().toPlainString()
                  + " 件/天"));
    }
    items.sort(
        Comparator.comparing(AiForecastOpportunityRow::detected)
            .reversed()
            .thenComparing(
                AiForecastOpportunityRow::trendRatio,
                Comparator.nullsLast(Comparator.reverseOrder())));
    var max = Math.min(Math.max(limit <= 0 ? 20 : limit, 1), MAX_PAGE_SIZE);
    return new AiForecastOpportunityView(
        items.subList(0, Math.min(items.size(), max)),
        window,
        OPPORTUNITY_TREND_THRESHOLD,
        OPPORTUNITY_MIN_DAILY_UNITS,
        "🔴 机会品 = 近期动销明显上升 **且** 基线销量达标；"
            + "预测与库存缺口是**参考信息**，读不到库存时只显示已算出的部分。");
  }

  // ================================================================ 预测引擎

  /**
   * 发起一次预测运行。
   *
   * <p>🔴 **建议只产出，不执行**：本次运行会落「补货量建议」与「调价建议」，
   * 但**不写任何下游表**、**不建补货单**、**不改价**。
   */
  public AiForecastRunDetail run(
      AiForecastRunInput input, AccessService.Access actor, String bearer) {
    var algorithm =
        input.algorithm() == null || input.algorithm().isBlank()
            ? "MOVING_AVERAGE"
            : input.algorithm().trim().toUpperCase(Locale.ROOT);
    check(
        algorithm.equals("MOVING_AVERAGE") || algorithm.equals("EXPONENTIAL_SMOOTHING"),
        "算法只能是 MOVING_AVERAGE 或 EXPONENTIAL_SMOOTHING");
    var maWindow =
        input.maWindowDays() == null ? DEFAULT_MA_WINDOW : input.maWindowDays();
    check(maWindow >= 1 && maWindow <= 30, "移动平均天数必须在 1 ~ 30 之间");
    var alpha = input.smoothingAlpha() == null ? DEFAULT_ALPHA : input.smoothingAlpha();
    check(alpha.signum() > 0 && alpha.compareTo(BigDecimal.ONE) < 0, "指数平滑 α 必须在 0 与 1 之间");
    var windowDays =
        input.windowDays() == null ? DEFAULT_WINDOW_DAYS : input.windowDays();
    check(windowDays >= 7 && windowDays <= 365, "统计窗口必须在 7 ~ 365 天之间");
    var horizonDays =
        input.horizonDays() == null ? DEFAULT_HORIZON_DAYS : input.horizonDays();
    check(horizonDays >= 1 && horizonDays <= 180, "预测期必须在 1 ~ 180 天之间");
    var confidence =
        input.confidenceLevel() == null ? DEFAULT_CONFIDENCE : input.confidenceLevel();
    var z = zFor(confidence);
    check(z != null, "置信水平只能是 0.90 / 0.95 / 0.99");
    var note = optional(input.note(), "备注", 500);

    var today = LocalDate.now(ZoneOffset.UTC);
    var windowFrom = today.minusDays(windowDays - 1);
    var forecastFrom = today.plusDays(1);
    var forecastTo = today.plusDays(horizonDays);

    // ---- 上游取数（读不到就如实标记，绝不拿 0 冒充） ----
    var onHandBySku = new LinkedHashMap<String, BigDecimal>();
    var inventoryConnected = true;
    try {
      var page = 0;
      while (page < PlatformUpstreamClient.MAX_PAGES) {
        var batch = inventory.balances(page, PlatformUpstreamClient.PAGE_SIZE, bearer);
        var rows = batch.rows() == null ? List.<WarehouseInventoryPort.BalanceRow>of() : batch.rows();
        for (var row : rows) {
          if (row.skuCode() == null) continue;
          onHandBySku.merge(row.skuCode(), num(row.onHand()), BigDecimal::add);
        }
        if (rows.isEmpty()) break;
        if (onHandBySku.size() >= batch.total() && batch.total() > 0) break;
        page++;
      }
    } catch (RuntimeException e) {
      inventoryConnected = false;
      onHandBySku.clear();
    }

    var inTransitBySku = new LinkedHashMap<String, BigDecimal>();
    var inTransitConnected = true;
    try {
      for (var row : inTransit.netting(bearer)) {
        if (row.skuCode() == null) continue;
        inTransitBySku.merge(row.skuCode(), num(row.outstandingQuantity()), BigDecimal::add);
      }
    } catch (RuntimeException e) {
      inTransitConnected = false;
      inTransitBySku.clear();
    }

    var pendingBySku = new LinkedHashMap<String, BigDecimal>();
    var purchaseConnected = true;
    try {
      for (var row : purchase.pendingBySku(windowFrom, today, bearer)) {
        pendingBySku.put(row.skuCode(), row.pendingQuantity());
      }
    } catch (RuntimeException e) {
      purchaseConnected = false;
      pendingBySku.clear();
    }

    // ---- 促销日历（租户自己的业务事实） ----
    var promotions =
        sql.fetch(
            "select id,name,promotion_type,scope_type,scope_value,starts_at,ends_at,discount_rate,"
                + " expected_lift from platform_forecast_promotion where starts_at <= ?::timestamptz"
                + " and ends_at >= ?::timestamptz",
            forecastTo.atStartOfDay().atOffset(ZoneOffset.UTC),
            forecastFrom.atStartOfDay().atOffset(ZoneOffset.UTC));

    // ---- 历史台账（按 SKU 分组） ----
    var history =
        sql.fetch(
            "select sku_code, sku_name, business_date, units, unit_price, revenue, currency, source"
                + " from platform_forecast_demand_history where business_date >= ?::date"
                + " and business_date <= ?::date order by sku_code, business_date",
            windowFrom,
            today);
    var bySku = new LinkedHashMap<String, List<HistoryPoint>>();
    for (var row : history) {
      var code = row.get("sku_code", String.class);
      bySku
          .computeIfAbsent(code, k -> new ArrayList<>())
          .add(
              new HistoryPoint(
                  code,
                  row.get("sku_name", String.class),
                  row.get("business_date", LocalDate.class),
                  num(row.get("units", BigDecimal.class)),
                  row.get("unit_price", BigDecimal.class),
                  row.get("currency", String.class),
                  row.get("source", String.class)));
    }

    // ---- 落运行记录 ----
    var runId = UUID.randomUUID();
    var seq = sql.fetchOne("select nextval('platform_forecast_run_no_seq') as seq").get("seq", Long.class);
    var tenant = currentTenant();
    var runNo = "FC-" + tenant.toString().substring(0, 8) + "-" + String.format("%06d", seq);
    var now = OffsetDateTime.now(ZoneOffset.UTC);

    var evaluated = bySku.size();
    var skipped = 0;
    var skippedReason = "";
    var results = new ArrayList<ForecastOutcome>();

    for (var entry : bySku.entrySet()) {
      var code = entry.getKey();
      var points = entry.getValue();
      var series = calendarSeries(points, windowFrom, today);
      var sampleDays = points.size();
      if (sampleDays < MIN_SAMPLE_DAYS) {
        skipped++;
        continue;
      }
      var outcome = computeOutcome(code, points, series, algorithm, maWindow, alpha, windowDays, horizonDays, z, confidence, promotions, forecastFrom, forecastTo);
      outcome.points = points;
      outcome.inventoryQty = onHandBySku.get(code);
      outcome.inTransitQty = inTransitBySku.get(code);
      outcome.purchasePendingQty = pendingBySku.get(code);
      outcome.historySource = points.get(points.size() - 1).source();
      results.add(outcome);
    }
    if (skipped > 0) {
      skippedReason =
          "有 "
              + skipped
              + " 个 SKU 因样本不足 "
              + MIN_SAMPLE_DAYS
              + " 天被跳过（没有给点估计，也没有给区间 —— 拍脑袋的窄区间比没有区间更危险）";
    }
    // 🔴 本次运行**实际**用到的历史来源：逐 SKU 记在结果行上；运行级是它们的汇总。
    var sources =
        results.stream().map(o -> o.historySource).distinct().sorted().toList();
    var historySource =
        sources.isEmpty() ? "MANUAL" : sources.size() == 1 ? sources.get(0) : "MIXED";

    sql.execute(
        "insert into platform_forecast_run(id,tenant_id,run_no,algorithm,ma_window_days,smoothing_alpha,"
            + " window_days,horizon_days,confidence_level,z_value,history_source,status,"
            + " evaluated_sku_count,forecast_sku_count,skipped_sku_count,skipped_reason,"
            + " inventory_connected,in_transit_connected,purchase_connected,note,requested_by,"
            + " started_at,finished_at)"
            + " values(?,?,?,?,?,?,?,?,?,?,?,'COMPLETED',?,?,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz)",
        runId,
        tenant,
        runNo,
        algorithm,
        maWindow,
        alpha,
        windowDays,
        horizonDays,
        confidence,
        z,
        historySource,
        evaluated,
        results.size(),
        skipped,
        skippedReason,
        inventoryConnected,
        inTransitConnected,
        purchaseConnected,
        note,
        actor.userId(),
        now,
        now);

    // ---- 落结果与影响因素 ----
    for (var outcome : results) {
      var resultId = UUID.randomUUID();
      sql.execute(
          "insert into platform_forecast_result(id,tenant_id,run_id,sku_code,sku_name,algorithm,"
              + " ma_window_days,smoothing_alpha,window_days,horizon_days,history_source,sample_days,"
              + " coverage_rate,baseline_daily,promo_factor,point,lower,upper,sigma,z_value,"
              + " confidence_level,inventory_qty,in_transit_qty,purchase_pending_qty,unit_price,"
              + " currency,created_at)"
              + " values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::timestamptz)",
          resultId,
          tenant,
          runId,
          outcome.skuCode,
          outcome.skuName,
          algorithm,
          maWindow,
          alpha,
          windowDays,
          horizonDays,
          outcome.historySource,
          outcome.sampleDays,
          outcome.coverageRate,
          outcome.baselineDaily,
          outcome.promoFactor,
          outcome.point,
          outcome.lower,
          outcome.upper,
          outcome.sigma,
          z,
          confidence,
          outcome.inventoryQty,
          outcome.inTransitQty,
          outcome.purchasePendingQty,
          outcome.unitPrice,
          outcome.currency,
          now);
      for (var factor : outcome.factors) {
        sql.execute(
            "insert into platform_forecast_factor(id,tenant_id,result_id,factor_code,factor_label,"
                + " direction,value,value_text,impact,explanation,source,created_at)"
                + " values(?,?,?,?,?,?,?,?,?,?,?,?::timestamptz)",
            UUID.randomUUID(),
            tenant,
            resultId,
            factor.factorCode(),
            factor.factorLabel(),
            factor.direction(),
            factor.value(),
            factor.valueText(),
            factor.impact(),
            factor.explanation(),
            factor.source(),
            now);
      }
      // ---- 建议（只产出，不执行） ----
      if (inventoryConnected && inTransitConnected) {
        var stock = num(outcome.inventoryQty);
        var transit = num(outcome.inTransitQty);
        var suggested = outcome.upper.subtract(stock).subtract(transit).max(BigDecimal.ZERO);
        var basis =
            "建议补货量 = max(0, 预测期需求上界 "
                + outcome.upper.stripTrailingZeros().toPlainString()
                + " − 在手库存 "
                + stock.stripTrailingZeros().toPlainString()
                + " − 在途净额 "
                + transit.stripTrailingZeros().toPlainString()
                + ") = "
                + suggested.stripTrailingZeros().toPlainString()
                + " 件。取**上界**是保守口径（宁可多备也别缺货）。"
                + "⚠️ 这只是建议，本模块**不建补货单**（那是 6 采购的写入所有权）。"
                + (outcome.purchasePendingQty == null
                    ? "采购侧未到货量读不到（归 6 采购）。"
                    : "采购侧未到货量 "
                        + outcome.purchasePendingQty.stripTrailingZeros().toPlainString()
                        + " 件**仅作参考**（7 库存的在途净额已含采购发运腿，不重复扣减）。");
        sql.execute(
            "insert into platform_forecast_suggestion(id,tenant_id,suggestion_type,run_id,result_id,"
                + " sku_code,sku_name,current_qty,in_transit_qty,purchase_pending_qty,suggested_qty,"
                + " demand_point,demand_lower,demand_upper,status,basis,created_at,updated_at)"
                + " values(?,?,'REPLENISH',?,?,?,?,?,?,?,?,?,?,?,'SUGGESTED',?,?::timestamptz,?::timestamptz)",
            UUID.randomUUID(),
            tenant,
            runId,
            resultId,
            outcome.skuCode,
            outcome.skuName,
            outcome.inventoryQty,
            outcome.inTransitQty,
            outcome.purchasePendingQty,
            suggested,
            outcome.point,
            outcome.lower,
            outcome.upper,
            basis,
            now,
            now);
      }
      // ---- 调价建议：只在弹性算得出来、且弹性够大时才给 ----
      var estimate = estimateElasticity(outcome.points);
      if (estimate.available() && estimate.elasticity().compareTo(PRICE_ELASTICITY_THRESHOLD) <= 0) {
        var currentPrice = latestPrice(outcome.points);
        if (currentPrice != null && currentPrice.signum() > 0) {
          var delta = MAX_PRICE_ADJUST_RATE.negate();
          var suggestedPrice = scale6(currentPrice.multiply(BigDecimal.ONE.add(delta)));
          var expectedDelta =
              scale6(estimate.elasticity().multiply(delta));
          var basis =
              "价格弹性 ≈ "
                  + estimate.elasticity().stripTrailingZeros().toPlainString()
                  + "（"
                  + estimate.pairs()
                  + " 组样本对，中位数法）。建议**下调 "
                  + MAX_PRICE_ADJUST_RATE.stripTrailingZeros().toPlainString()
                  + "%** 做一次试探（幅度上限 "
                  + MAX_PRICE_ADJUST_RATE.stripTrailingZeros().toPlainString()
                  + "%），预计销量变化 ≈ "
                  + expectedDelta.multiply(new BigDecimal("100")).stripTrailingZeros().toPlainString()
                  + "%。⚠️ 这只是建议，本模块**不改价**（那是 5 定价的写入所有权）。";
          sql.execute(
              "insert into platform_forecast_suggestion(id,tenant_id,suggestion_type,run_id,result_id,"
                  + " sku_code,sku_name,current_price,suggested_price,price_delta_rate,"
                  + " expected_units_delta_rate,elasticity,currency,demand_point,demand_lower,"
                  + " demand_upper,status,basis,created_at,updated_at)"
                  + " values(?,?,'PRICE',?,?,?,?,?,?,?,?,?,?,?,?,?,'SUGGESTED',?,?::timestamptz,?::timestamptz)",
              UUID.randomUUID(),
              tenant,
              runId,
              resultId,
              outcome.skuCode,
              outcome.skuName,
              currentPrice,
              suggestedPrice,
              delta,
              expectedDelta,
              estimate.elasticity(),
              latestCurrency(outcome.points),
              outcome.point,
              outcome.lower,
              outcome.upper,
              basis,
              now,
              now);
        }
      }
    }
    return runDetail(runId);
  }

  // ================================================================ 引擎内部

  /** 一天一个点（缺记录的日子按 0 销量计）。 */
  private record HistoryPoint(
      String skuCode,
      String name,
      LocalDate date,
      BigDecimal units,
      BigDecimal unitPrice,
      String currency,
      String source) {}

  /** 一个影响因素。 */
  private record Factor(
      String factorCode,
      String factorLabel,
      String direction,
      BigDecimal value,
      String valueText,
      BigDecimal impact,
      String explanation,
      String source) {}

  /** 一个 SKU 的预测结果（落库前）。 */
  private static final class ForecastOutcome {
    String skuCode;
    String skuName;
    int sampleDays;
    BigDecimal coverageRate;
    BigDecimal baselineDaily;
    BigDecimal promoFactor = BigDecimal.ONE;
    BigDecimal point;
    BigDecimal lower;
    BigDecimal upper;
    BigDecimal sigma;
    BigDecimal unitPrice;
    String currency;
    String historySource = "MANUAL";
    BigDecimal inventoryQty;
    BigDecimal inTransitQty;
    BigDecimal purchasePendingQty;
    List<Factor> factors = new ArrayList<>();
    List<HistoryPoint> points = new ArrayList<>();
  }

  /** 弹性估计的结果。 */
  private record ElasticityEstimate(
      boolean available, BigDecimal elasticity, int pairs, String reason, String basis) {}

  /** 把台账里的点补成整条日历序列（缺失的日子按 0 销量计）。 */
  private static List<BigDecimal> calendarSeries(
      List<HistoryPoint> points, LocalDate from, LocalDate to) {
    var byDate = new HashMap<LocalDate, BigDecimal>();
    for (var point : points) {
      byDate.merge(point.date(), num(point.units()), BigDecimal::add);
    }
    var series = new ArrayList<BigDecimal>();
    for (var day = from; !day.isAfter(to); day = day.plusDays(1)) {
      series.add(byDate.getOrDefault(day, BigDecimal.ZERO));
    }
    return series;
  }

  /**
   * 算一个 SKU 的预测结果（点估计 + 区间 + 影响因素）。
   *
   * <p>🔴 **区间是算出来的，不是拍的**：σ = 一步预测误差的样本标准差（分母 n-1），
   * 区间 = 点估计 ± z × σ × √预测天数。
   */
  private ForecastOutcome computeOutcome(
      String code,
      List<HistoryPoint> points,
      List<BigDecimal> series,
      String algorithm,
      int maWindow,
      BigDecimal alpha,
      int windowDays,
      int horizonDays,
      BigDecimal z,
      BigDecimal confidence,
      List<org.jooq.Record> promotions,
      LocalDate forecastFrom,
      LocalDate forecastTo) {
    var outcome = new ForecastOutcome();
    outcome.skuCode = code;
    outcome.skuName = points.get(points.size() - 1).name();
    outcome.sampleDays = points.size();
    outcome.coverageRate = scale6(BigDecimal.valueOf(points.size()).divide(BigDecimal.valueOf(windowDays), 6, RoundingMode.HALF_UP));

    BigDecimal baseline;
    List<BigDecimal> residuals = new ArrayList<>();
    if (algorithm.equals("EXPONENTIAL_SMOOTHING")) {
      var level = series.get(0);
      for (int i = 1; i < series.size(); i++) {
        residuals.add(series.get(i).subtract(level));
        level = alpha.multiply(series.get(i)).add(BigDecimal.ONE.subtract(alpha).multiply(level));
      }
      baseline = level;
    } else {
      var k = Math.min(maWindow, series.size());
      var sum = BigDecimal.ZERO;
      for (int i = series.size() - k; i < series.size(); i++) sum = sum.add(series.get(i));
      baseline = sum.divide(BigDecimal.valueOf(k), 6, RoundingMode.HALF_UP);
      for (int i = 1; i < series.size(); i++) {
        var from = Math.max(0, i - k);
        var windowSum = BigDecimal.ZERO;
        for (int j = from; j < i; j++) windowSum = windowSum.add(series.get(j));
        var mean = windowSum.divide(BigDecimal.valueOf(i - from), 6, RoundingMode.HALF_UP);
        residuals.add(series.get(i).subtract(mean));
      }
    }
    outcome.baselineDaily = scale6(baseline);

    // 残差标准差（分母 n-1）。
    double sumSquares = 0;
    for (var residual : residuals) sumSquares += residual.doubleValue() * residual.doubleValue();
    var sigma =
        residuals.size() >= 2
            ? scale6(
                BigDecimal.valueOf(Math.sqrt(sumSquares / (residuals.size() - 1)))
                    .setScale(10, RoundingMode.HALF_UP))
            : BigDecimal.ZERO;
    outcome.sigma = sigma;

    // 促销因子：预测期内生效的促销取**最大**一个（不叠加）。
    var promoFactor = BigDecimal.ONE;
    org.jooq.Record bestPromo = null;
    for (var promo : promotions) {
      var scopeType = promo.get("scope_type", String.class);
      var scopeValue = promo.get("scope_value", String.class);
      if ("SKU".equals(scopeType) && !code.equals(scopeValue)) continue;
      var lift = promo.get("expected_lift", BigDecimal.class);
      if (lift == null) continue;
      if (BigDecimal.ONE.add(lift).compareTo(promoFactor) > 0) {
        promoFactor = BigDecimal.ONE.add(lift);
        bestPromo = promo;
      }
    }
    outcome.promoFactor = scale6(promoFactor);

    var point = scale6(baseline.multiply(promoFactor).multiply(BigDecimal.valueOf(horizonDays)));
    var halfWidth =
        scale6(
            z.multiply(sigma)
                .multiply(BigDecimal.valueOf(Math.sqrt((double) horizonDays)))
                .setScale(10, RoundingMode.HALF_UP));
    outcome.point = point;
    outcome.lower = point.subtract(halfWidth).max(BigDecimal.ZERO);
    outcome.upper = point.add(halfWidth);

    // ---- 影响因素 ----
    outcome.factors.add(
        new Factor(
            "BASE_LEVEL",
            "基线水平",
            "NEUTRAL",
            outcome.baselineDaily,
            "日均 " + outcome.baselineDaily.stripTrailingZeros().toPlainString() + " 件",
            scale6(baseline.multiply(promoFactor).multiply(BigDecimal.valueOf(horizonDays))),
            algorithm.equals("EXPONENTIAL_SMOOTHING")
                ? "指数平滑（α = " + alpha.stripTrailingZeros().toPlainString()
                    + "）的最后水平 = 预测底子；预测期 " + horizonDays + " 天共 "
                    + outcome.point.stripTrailingZeros().toPlainString() + " 件。"
                : "最近 " + Math.min(maWindow, series.size()) + " 天的移动平均 = 预测底子；预测期 "
                    + horizonDays + " 天共 " + outcome.point.stripTrailingZeros().toPlainString() + " 件。",
            "platform_forecast_demand_history"));

    if (bestPromo != null) {
      var lift = bestPromo.get("expected_lift", BigDecimal.class);
      var impact =
          scale6(
              outcome.baselineDaily
                  .multiply(lift)
                  .multiply(BigDecimal.valueOf(horizonDays)));
      outcome.factors.add(
          new Factor(
              "PROMOTION",
              "促销 / 大促",
              lift.signum() > 0 ? "UP" : lift.signum() < 0 ? "DOWN" : "NEUTRAL",
              lift,
              bestPromo.get("name", String.class),
              impact,
              "促销「"
                  + bestPromo.get("name", String.class)
                  + "」（"
                  + bestPromo.get("starts_at", OffsetDateTime.class)
                  + " ~ "
                  + bestPromo.get("ends_at", OffsetDateTime.class)
                  + "）在预测期内生效，预期销量提升 "
                  + lift.multiply(new BigDecimal("100")).stripTrailingZeros().toPlainString()
                  + "%，因此预测量上移 "
                  + impact.stripTrailingZeros().toPlainString()
                  + " 件。多个促销重叠时取**最大**一个（不叠加）。",
              "platform_forecast_promotion（租户录入，非上游同步）"));
    } else {
      outcome.factors.add(
          new Factor(
              "PROMOTION",
              "促销 / 大促",
              "NEUTRAL",
              null,
              "预测期内没有登记生效的促销",
              BigDecimal.ZERO,
              "促销日历里没有与预测期有交集的记录 ⇒ 促销因子 = 1（这是「没有登记」，"
                  + "不是「确认没有促销」）。",
              "platform_forecast_promotion（租户录入，非上游同步）"));
    }

    var seasonality = seasonalityFactor(points);
    if (seasonality == null) {
      outcome.factors.add(
          new Factor(
              "SEASONALITY",
              "星期季节性",
              "NEUTRAL",
              null,
              "样本不足，未计算",
              BigDecimal.ZERO,
              "星期季节性至少要 14 天样本（两个完整星期）；当前只有 "
                  + points.size()
                  + " 天 ⇒ 这一维**不适用**，没有参与基线调整。",
              "platform_forecast_demand_history"));
    } else {
      outcome.factors.add(
          new Factor(
              "SEASONALITY",
              "星期季节性",
              seasonality.compareTo(BigDecimal.ONE) > 0
                  ? "UP"
                  : seasonality.compareTo(BigDecimal.ONE) < 0 ? "DOWN" : "NEUTRAL",
              seasonality,
              "同星期几均值 ÷ 总体均值",
              BigDecimal.ZERO,
              "按历史同星期几的日均销量与总体日均之比 = "
                  + seasonality.stripTrailingZeros().toPlainString()
                  + "。⚠️ 该比值只作**说明**：基线用的是整条日历序列的均值，"
                  + "这一比值**没有**再乘进点估计（否则会重复计入）。",
              "platform_forecast_demand_history"));
    }

    var priceChange = priceChangeFactor(points);
    if (priceChange == null) {
      outcome.factors.add(
          new Factor(
              "PRICE_CHANGE",
              "价格变动",
              "NEUTRAL",
              null,
              "窗口内没有可比较的价格",
              BigDecimal.ZERO,
              "台账里没有足够的单价记录，或窗口内价格没变过（上游的销量口径**不带价格**）⇒ "
                  + "这一维**不适用**，价格变化的影响未被单独剥离。",
              "platform_forecast_demand_history"));
    } else {
      outcome.factors.add(
          new Factor(
              "PRICE_CHANGE",
              "价格变动",
              priceChange.rate().signum() > 0
                  ? "UP"
                  : priceChange.rate().signum() < 0 ? "DOWN" : "NEUTRAL",
              priceChange.rate(),
              priceChange.text(),
              BigDecimal.ZERO,
              "窗口内单价从 "
                  + priceChange.from().stripTrailingZeros().toPlainString()
                  + " 变为 "
                  + priceChange.to().stripTrailingZeros().toPlainString()
                  + "（"
                  + priceChange.rate().multiply(new BigDecimal("100")).stripTrailingZeros().toPlainString()
                  + "%）。⚠️ 该变化**未**从基线里剥离：基线用的是窗口末段的销量，"
                  + "已经部分反映了新价格；其波动体现在区间宽度里。",
              "platform_forecast_demand_history"));
    }

    outcome.factors.add(
        new Factor(
            "DATA_QUALITY",
            "样本覆盖度",
            "NEUTRAL",
            outcome.coverageRate,
            points.size() + " / " + windowDays + " 天有记录",
            BigDecimal.ZERO,
            "统计窗口 " + windowDays + " 天里只有 " + points.size()
                + " 天有销量记录（覆盖 " + outcome.coverageRate.stripTrailingZeros().toPlainString()
                + "%）。没有记录的日子按 0 销量计 —— 若台账只登记了有销量的日子，"
                + "基线会**偏低**；覆盖度越低，区间的可信度越弱。",
            "platform_forecast_demand_history"));

    outcome.unitPrice = latestPrice(points);
    outcome.currency = latestCurrency(points);
    return outcome;
  }

  /** 星期季节性（同星期几均值 ÷ 总体均值）；样本不足返回 null。 */
  private static BigDecimal seasonalityFactor(List<HistoryPoint> points) {
    if (points.size() < 14) return null;
    var byWeekday = new HashMap<Integer, List<BigDecimal>>();
    var all = new ArrayList<BigDecimal>();
    for (var point : points) {
      byWeekday.computeIfAbsent(point.date().getDayOfWeek().getValue(), k -> new ArrayList<>()).add(point.units());
      all.add(point.units());
    }
    var overall = avg(all);
    if (overall.signum() == 0) return null;
    var factors = new ArrayList<BigDecimal>();
    for (var bucket : byWeekday.values()) {
      var mean = avg(bucket);
      if (mean.signum() == 0) continue;
      factors.add(mean.divide(overall, 6, RoundingMode.HALF_UP));
    }
    if (factors.isEmpty()) return null;
    factors.sort(Comparator.naturalOrder());
    return factors.get(factors.size() / 2);
  }

  /** 窗口内的价格变动；不可比较时返回 null。 */
  private static PriceChange priceChangeFactor(List<HistoryPoint> points) {
    BigDecimal from = null;
    BigDecimal to = null;
    for (var point : points) {
      if (point.unitPrice() == null || point.unitPrice().signum() <= 0) continue;
      if (from == null) from = point.unitPrice();
      to = point.unitPrice();
    }
    if (from == null || to == null || from.signum() == 0) return null;
    var rate = to.subtract(from).divide(from, 6, RoundingMode.HALF_UP);
    if (rate.signum() == 0) return null;
    return new PriceChange(from, to, rate, from.stripTrailingZeros().toPlainString() + " → "
        + to.stripTrailingZeros().toPlainString());
  }

  private record PriceChange(BigDecimal from, BigDecimal to, BigDecimal rate, String text) {}

  /**
   * 估计价格弹性。
   *
   * <p>口径：相邻两日「Δln(销量) / Δln(单价)」的**中位数**。
   * 销量为 0 或价格没变的那几天**不参与**（除零与「没有变化」都不是弹性）。
   */
  private static ElasticityEstimate estimateElasticity(List<HistoryPoint> points) {
    var samples = new ArrayList<BigDecimal>();
    for (int i = 1; i < points.size(); i++) {
      var previous = points.get(i - 1);
      var current = points.get(i);
      if (previous.unitPrice() == null || current.unitPrice() == null) continue;
      if (previous.units() == null || current.units() == null) continue;
      if (previous.unitPrice().signum() <= 0 || current.unitPrice().signum() <= 0) continue;
      if (previous.units().signum() <= 0 || current.units().signum() <= 0) continue;
      var priceRatio =
          current.unitPrice().divide(previous.unitPrice(), 12, RoundingMode.HALF_UP);
      if (BigDecimal.ONE.compareTo(priceRatio) == 0) continue;
      var unitsRatio = current.units().divide(previous.units(), 12, RoundingMode.HALF_UP);
      if (BigDecimal.ONE.compareTo(unitsRatio) == 0) continue;
      samples.add(
          scale6(
              log(unitsRatio)
                  .divide(log(priceRatio), 12, RoundingMode.HALF_UP)));
    }
    if (samples.size() < 2) {
      return new ElasticityEstimate(
          false,
          null,
          samples.size(),
          samples.isEmpty()
              ? "窗口内没有可用的价格/销量样本对（上游的销量口径不带价格，"
                  + "需要人工录入单价；或窗口内价格没变过）"
              : "只有 1 组样本对，至少要 2 组才能估出弹性",
          "相邻两日 Δln(销量) / Δln(单价) 的中位数");
    }
    samples.sort(Comparator.naturalOrder());
    BigDecimal median;
    if (samples.size() % 2 == 1) {
      median = samples.get(samples.size() / 2);
    } else {
      median =
          samples
              .get(samples.size() / 2 - 1)
              .add(samples.get(samples.size() / 2))
              .divide(BigDecimal.valueOf(2), 6, RoundingMode.HALF_UP);
    }
    return new ElasticityEstimate(
        true,
        median,
        samples.size(),
        "",
        "相邻两日 Δln(销量) / Δln(单价) 的中位数（" + samples.size() + " 组样本对）");
  }

  private static BigDecimal latestPrice(List<HistoryPoint> points) {
    for (int i = points.size() - 1; i >= 0; i--) {
      if (points.get(i).unitPrice() != null) return points.get(i).unitPrice();
    }
    return null;
  }

  private static String latestCurrency(List<HistoryPoint> points) {
    for (int i = points.size() - 1; i >= 0; i--) {
      if (points.get(i).currency() != null && !points.get(i).currency().isBlank()) {
        return points.get(i).currency();
      }
    }
    return null;
  }

  /** 一段窗口内的日均销量（按**日历天**算，缺记录的日子算 0）。 */
  private static BigDecimal averageDaily(
      List<HistoryPoint> points, LocalDate from, LocalDate to) {
    var byDate = new HashMap<LocalDate, BigDecimal>();
    for (var point : points) {
      if (point.date().isBefore(from) || point.date().isAfter(to)) continue;
      byDate.merge(point.date(), num(point.units()), BigDecimal::add);
    }
    if (byDate.isEmpty()) return null;
    var days = (int) (to.toEpochDay() - from.toEpochDay() + 1);
    var sum = BigDecimal.ZERO;
    for (var value : byDate.values()) sum = sum.add(value);
    return sum.divide(BigDecimal.valueOf(days), 6, RoundingMode.HALF_UP);
  }

  /**
   * 自然对数。
   *
   * <p>⚠️ {@code BigDecimal} 在 JDK 21 上**没有** {@code ln()}（JDK 24 才加），
   * 而 BigDecimal 的对数本身也没有十进制精确解 ⇒ 这里用 {@code Math.log} 取双精度，
   * 再**立刻**收回到 6 位小数的 BigDecimal：弹性是**比率**，不是金额，
   * 双精度带来的误差在 6 位小数上早已不可见；落库的仍然是 NUMERIC(19,6)，**不落浮点**。
   */
  private static BigDecimal log(BigDecimal value) {
    return BigDecimal.valueOf(Math.log(value.doubleValue())).setScale(12, RoundingMode.HALF_UP);
  }

  private static BigDecimal avg(List<BigDecimal> values) {
    if (values.isEmpty()) return BigDecimal.ZERO;
    var sum = BigDecimal.ZERO;
    for (var value : values) sum = sum.add(value);
    return sum.divide(BigDecimal.valueOf(values.size()), 6, RoundingMode.HALF_UP);
  }

  /** 取一个 SKU 在一段窗口内的历史点（按日期升序）。 */
  private List<HistoryPoint> historySeries(String code, LocalDate from, LocalDate to) {
    return sql.fetch(
            "select sku_code, sku_name, business_date, units, unit_price, currency, source"
                + " from platform_forecast_demand_history where sku_code = ? and business_date >= ?::date"
                + " and business_date <= ?::date order by business_date",
            code,
            from,
            to)
        .map(
            r ->
                new HistoryPoint(
                    r.get("sku_code", String.class),
                    r.get("sku_name", String.class),
                    r.get("business_date", LocalDate.class),
                    num(r.get("units", BigDecimal.class)),
                    r.get("unit_price", BigDecimal.class),
                    r.get("currency", String.class),
                    r.get("source", String.class)));
  }

  /** 一段窗口内按 SKU 的销量合计。 */
  private Map<String, BigDecimal> windowUnits(LocalDate from, LocalDate to) {
    var out = new LinkedHashMap<String, BigDecimal>();
    sql.fetch(
            "select sku_code, sum(units) as units from platform_forecast_demand_history"
                + " where business_date >= ?::date and business_date <= ?::date group by sku_code",
            from,
            to)
        .forEach(
            r ->
                out.put(
                    r.get("sku_code", String.class),
                    num(r.get("units", BigDecimal.class))));
    return out;
  }

  /** 最近一次运行里某个 SKU 的预测结果（机会品视图用）。 */
  private AiForecastResultRow latestResult(String code) {
    return java.util.Optional.ofNullable(
            sql.fetchOne(
                "select id,run_id,sku_code,sku_name,algorithm,ma_window_days,smoothing_alpha,window_days,"
                    + " horizon_days,history_source,sample_days,coverage_rate,baseline_daily,promo_factor,"
                    + " point,lower,upper,sigma,z_value,confidence_level,inventory_qty,in_transit_qty,"
                    + " purchase_pending_qty,unit_price,currency,created_at from platform_forecast_result"
                    + " where sku_code = ? order by created_at desc limit 1",
                code))
        .map(AiForecastService::toResultRow)
        .orElse(null);
  }

  // ================================================================ 小工具

  private static long pages(long total, int size) {
    return total <= 0 ? 0 : (total + size - 1) / size;
  }

  private static Object[] concat(Object[] head, Object... tail) {
    var out = new Object[head.length + tail.length];
    System.arraycopy(head, 0, out, 0, head.length);
    System.arraycopy(tail, 0, out, head.length, tail.length);
    return out;
  }

  private static String percent(BigDecimal rate) {
    return rate.multiply(new BigDecimal("100")).stripTrailingZeros().toPlainString() + "%";
  }

  private static AiForecastRunRow toRunRow(org.jooq.Record r) {
    return new AiForecastRunRow(
        r.get("id", UUID.class),
        r.get("run_no", String.class),
        r.get("algorithm", String.class),
        r.get("ma_window_days", Integer.class),
        r.get("smoothing_alpha", BigDecimal.class),
        r.get("window_days", Integer.class),
        r.get("horizon_days", Integer.class),
        r.get("confidence_level", BigDecimal.class),
        r.get("z_value", BigDecimal.class),
        r.get("history_source", String.class),
        r.get("status", String.class),
        r.get("evaluated_sku_count", Integer.class),
        r.get("forecast_sku_count", Integer.class),
        r.get("skipped_sku_count", Integer.class),
        r.get("skipped_reason", String.class),
        r.get("inventory_connected", Boolean.class),
        r.get("in_transit_connected", Boolean.class),
        r.get("purchase_connected", Boolean.class),
        r.get("note", String.class),
        r.get("started_at", OffsetDateTime.class),
        r.get("finished_at", OffsetDateTime.class));
  }

  private static AiForecastResultRow toResultRow(org.jooq.Record r) {
    var horizon = r.get("horizon_days", Integer.class);
    var point = r.get("point", BigDecimal.class);
    var daily =
        horizon == null || horizon <= 0 || point == null
            ? null
            : scale6(point.divide(BigDecimal.valueOf(horizon), 6, RoundingMode.HALF_UP));
    return new AiForecastResultRow(
        r.get("id", UUID.class),
        r.get("run_id", UUID.class),
        r.get("sku_code", String.class),
        r.get("sku_name", String.class),
        r.get("algorithm", String.class),
        r.get("ma_window_days", Integer.class),
        r.get("smoothing_alpha", BigDecimal.class),
        r.get("window_days", Integer.class),
        r.get("horizon_days", Integer.class),
        r.get("history_source", String.class),
        r.get("sample_days", Integer.class),
        r.get("coverage_rate", BigDecimal.class),
        r.get("baseline_daily", BigDecimal.class),
        r.get("promo_factor", BigDecimal.class),
        point,
        r.get("lower", BigDecimal.class),
        r.get("upper", BigDecimal.class),
        daily,
        r.get("sigma", BigDecimal.class),
        r.get("z_value", BigDecimal.class),
        r.get("confidence_level", BigDecimal.class),
        r.get("inventory_qty", BigDecimal.class),
        r.get("in_transit_qty", BigDecimal.class),
        r.get("purchase_pending_qty", BigDecimal.class),
        r.get("unit_price", BigDecimal.class),
        r.get("currency", String.class),
        r.get("created_at", OffsetDateTime.class));
  }
}
