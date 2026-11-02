package com.starcon.platform;

import static com.starcon.platform.AiDiagnosisModels.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 18.4.2.3「异常诊断」的服务层。
 *
 * <p>数据访问一律走 <b>jOOQ 显式 SQL</b>（AGENTS.md：不用 JPA 懒加载掩盖关键逻辑）。
 * 租户上下文由 {@link AccessService#run} 绑定 —— 本类<b>不</b>读任何请求头里的租户。
 *
 * <p><b>七条纪律：</b>
 *
 * <ol>
 *   <li><b>🔴 绝不假装调用了 AI 模型</b>。本仓没有任何 LLM 推理服务 ⇒ 本模块做的是
 *       <b>确定性、可解释归因</b>：按写死的阈值与口径，把**真实存在的**事实行与异常对上。
 *   <li><b>🔴 结论必须带依据</b>。每条结论至少一条 {@code platform_diagnosis_evidence}
 *       （事实表 + 字段 + 数值 + 快照时间 + 下钻引用）。
 *       <b>读不到支撑事实 ⇒ 那一条根本不出结论</b>，而不是出一条没有依据的结论。
 *   <li><b>🔴 上游读不到就 503</b>，绝不用空集合冒充「上游没有数据」：
 *       一个空集合在页面上会长成「这个 SKU 没问题」，而真相是「这一格没读出来」。
 *   <li><b>🔴 异常事件来源只有两条</b>：调用方提交 + 平台内部失败源同步
 *       （工作台待办表与各域异常事件表**不存在**）—— 这条限制写进能力声明。
 *   <li><b>🔴 只读上游</b>：V413 / V414 / V422 的事实表一个字都不改；
 *       本模块只往自己的四张新表里写。
 *   <li><b>🔴 金额与数量用 BigDecimal</b>，百分比走**文本**通道（禁浮点）。
 *   <li><b>写入归属会话租户</b>：所有 INSERT 的 {@code tenant_id} 取自已认证会话，
 *       不接受请求参数传入；RLS 的 WITH CHECK 与触发器再兜一层。
 * </ol>
 */
@Service
public class AiDiagnosisService {

  // ================================================================ 口径常量

  /** 列表分页上限（真分页的上限，防止一次拉爆）。 */
  static final int MAX_PAGE_SIZE = 100;

  /** 🔴 **规则集版本**：归因口径会演进（阈值会调）⇒ 随运行落库，事后能说清「当时按哪一版判的」。 */
  static final String RULE_VERSION = "v1";

  /** 归因窗口：近段与前段各多少天（用于「比上一周期涨了多少」）。 */
  static final int RECENT_WINDOW_DAYS = 7;

  /** 归因窗口：前一段的天数（近段 + 前段 = 总窗口 14 天）。 */
  static final int PRIOR_WINDOW_DAYS = 7;

  /** 🔴 **库存：预占占在手比例阈值**（0.9 = 90%）。超过即认为「库存被占住卖不出去」。 */
  static final BigDecimal RESERVED_RATIO_THRESHOLD = new BigDecimal("0.90");

  /** 🔴 **库存：销量突增倍数阈值**（近 7 天日均 ÷ 前 7 天日均 ≥ 1.5 即算突增）。 */
  static final BigDecimal SALES_SURGE_RATIO = new BigDecimal("1.50");

  /** 🔴 **利润：采购单价上涨阈值**（近段均价 ÷ 前段均价 ≥ 1.15 即算成本上涨 15%）。 */
  static final BigDecimal COST_SPIKE_RATIO = new BigDecimal("1.15");

  /** 🔴 **利润：成交均价下跌阈值**（近段均价 ÷ 前段均价 ≤ 0.85 即算跌 15%）。 */
  static final BigDecimal PRICE_DROP_RATIO = new BigDecimal("0.85");

  /** 🔴 **利润：成本占销售额比阈值**（0.8 = 80%）。 */
  static final BigDecimal COST_RATIO_THRESHOLD = new BigDecimal("0.80");

  /** 🔴 **发货延迟：订单→出库耗时阈值**（秒）。24 小时。 */
  static final long ORDER_TO_PACK_THRESHOLD_SECONDS = 86_400L;

  /** 🔴 **发货延迟：出库→交接耗时阈值**（秒）。12 小时。 */
  static final long PACK_TO_HANDOVER_THRESHOLD_SECONDS = 43_200L;

  /** 🔴 **广告亏损：ACOS 阈值**（0.3 = 30%）。 */
  static final BigDecimal ACOS_THRESHOLD = new BigDecimal("0.30");

  /** 🔴 **广告亏损：ROAS 盈亏平衡阈值**（1.0 = 刚好回本）。 */
  static final BigDecimal ROAS_BREAKEVEN = new BigDecimal("1.00");

  /** 🔴 **广告亏损：CTR 阈值**（0.005 = 0.5%）。 */
  static final BigDecimal CTR_THRESHOLD = new BigDecimal("0.005");

  /** 内部失败源同步：每次最多扫多少条事实。 */
  static final int SYNC_SCAN_LIMIT = 100;

  // ================================================================ 依赖

  private final DSLContext sql;
  private final WarehouseInventoryPort inventory;
  private final ForecastSalesPort sales;
  private final DiagnosisPurchasePort purchase;
  private final DiagnosisTimelinessPort timeliness;
  private final DiagnosisAdPort ads;

  public AiDiagnosisService(
      DSLContext sql,
      WarehouseInventoryPort inventory,
      ForecastSalesPort sales,
      DiagnosisPurchasePort purchase,
      DiagnosisTimelinessPort timeliness,
      DiagnosisAdPort ads) {
    this.sql = sql;
    this.inventory = inventory;
    this.sales = sales;
    this.purchase = purchase;
    this.timeliness = timeliness;
    this.ads = ads;
  }

  // ================================================================ 内部结构

  /**
   * 一条**依据**（内部结构，落库前组装）。
   *
   * @param factValue 数值（禁浮点）；文字型取值写 {@code factValueText}
   * @param factRef 下钻引用：拿着它能回到那条事实行
   */
  private record Evidence(
      String factService,
      String factTable,
      String factField,
      BigDecimal factValue,
      String factValueText,
      String factUnit,
      String factRef,
      OffsetDateTime factOccurredAt,
      String explanation) {}

  /**
   * 一条**归因结论**（内部结构，落库前组装）。
   *
   * @param evidences 支撑这条结论的全部依据（**至少一条**）
   */
  private record Finding(
      String ruleCode,
      String ruleLabel,
      String severity,
      String summary,
      String detail,
      BigDecimal impact,
      String impactUnit,
      String impactText,
      String recommendation,
      String actionRef,
      String actionOwner,
      List<Evidence> evidences) {}

  /** 一次运行里「读了哪些源 / 哪一格读不到」的记账。 */
  private static final class RunContext {
    final Set<String> read = new LinkedHashSet<>();
    final Set<String> unavailable = new LinkedHashSet<>();
    final List<Finding> findings = new ArrayList<>();

    void markRead(String source) {
      read.add(source);
    }

    void markUnavailable(String source, String reason) {
      unavailable.add(source + "(" + reason + ")");
    }
  }

  // ================================================================ 工具

  private static void check(boolean condition, String message) {
    if (!condition) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  /** 状态冲突（409）：不是「参数不对」，是「当前状态不允许」。 */
  private static void conflict(String message) {
    throw new ResponseStatusException(HttpStatus.CONFLICT, message);
  }

  /** 找不到（404）。 */
  private static void notFound(String message) {
    throw new ResponseStatusException(HttpStatus.NOT_FOUND, message);
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

  private static long pages(long total, int limit) {
    return limit <= 0 ? 0 : (total + limit - 1) / limit;
  }

  private static Object[] concat(Object[] head, Object... tail) {
    var out = new Object[head.length + tail.length];
    System.arraycopy(head, 0, out, 0, head.length);
    System.arraycopy(tail, 0, out, head.length, tail.length);
    return out;
  }

  private static BigDecimal num(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }

  /** 比率保留 6 位（与库里的 numeric(9,6) 对齐）。 */
  private static BigDecimal scale6(BigDecimal value) {
    return value.setScale(6, RoundingMode.HALF_UP);
  }

  /**
   * 比率 → **百分比文本**（如 {@code "+18.40%"}）。
   *
   * <p>🔴 **禁浮点**：全程 BigDecimal，最后一步才转字符串。
   */
  static String percentText(BigDecimal rate) {
    if (rate == null) return "";
    var pct = rate.multiply(new BigDecimal("100")).setScale(2, RoundingMode.HALF_UP);
    var sign = pct.signum() >= 0 ? "+" : "";
    return sign + pct.toPlainString() + "%";
  }

  /** 变化率 = (新 − 旧) ÷ 旧（旧 ≤ 0 时给 null —— 除零没有意义）。 */
  static BigDecimal changeRate(BigDecimal now, BigDecimal before) {
    if (now == null || before == null) return null;
    if (before.signum() == 0) return null;
    return scale6(now.subtract(before).divide(before, 6, RoundingMode.HALF_UP));
  }

  /** 异常类别 → 中文标签（页面照抄，不自己造第二套）。 */
  static String anomalyLabel(String type) {
    if (type == null) return "";
    return switch (type) {
      case "INVENTORY" -> "库存异常";
      case "PROFIT" -> "利润异常";
      case "SHIPPING_DELAY" -> "发货延迟";
      case "SYNC_FAILURE" -> "同步失败";
      case "AD_LOSS" -> "广告亏损";
      default -> type;
    };
  }

  // ================================================================ 能力声明

  /**
   * 18.4.2.3 的能力声明：逐条说清「有没有、依据是什么、缺了会怎样」。
   *
   * <p>🔴 **能力边界写在最前面**：本仓没有 LLM 推理服务 ⇒ 本模块做确定性归因；
   * 工作台待办表与各域异常事件表**不存在** ⇒ 异常事件来源只有两条。
   */
  public AiDiagnosisCapabilities capabilities() {
    var capabilities =
        List.of(
            new AiDiagnosisCapability(
                "18.4.2.3",
                "异常事件登记（**两条来源**）",
                true,
                "① SUBMITTED：调用方经 POST /platform/ai/diagnosis/events 提交；"
                    + "② PLATFORM_INTERNAL：从 platform_delivery_record（V413）、"
                    + "platform_api_call_log（V414）、platform_automation_rule_run（V422）聚合",
                "",
                "🔴 **工作台待办表与各域异常事件表不存在**（卷 01 / 13 / 14 / 16 未交付）⇒ "
                    + "本模块**不假装**能读到它们；页面上如实写明"),
            new AiDiagnosisCapability(
                "18.4.2.3",
                "**确定性归因**（可解释 · 可复算）",
                true,
                "22 条规则写死在服务层，命中条件、依据表、阈值逐条报在 rules / thresholds 里；"
                    + "每次运行把 rule_version 落库",
                "",
                "🔴 **不是 AI 大模型**：本仓没有任何 LLM 推理服务，"
                    + "本模块做的是「按已存在的指标事实与异常对上」的确定性分析；"
                    + "**绝不返回编造的诊断结论**"),
            new AiDiagnosisCapability(
                "18.4.2.3",
                "🔴 **可下钻的依据**（事实表 + 字段 + 数值 + 快照时间）",
                true,
                "platform_diagnosis_evidence 表：fact_service / fact_table / fact_field / fact_value / "
                    + "snapshot_at / fact_ref；每条结论至少一条依据",
                "",
                "🔴 **读不到支撑事实就不出那条结论** —— "
                    + "运行记录里写明「哪一格读不到」，而不是给一条没依据的结论"),
            new AiDiagnosisCapability(
                "18.4.2.3",
                "库存异常归因（可用 / 超储 / 预占 / 销量突增）",
                true,
                "warehouse / inventory_balance（on_hand / reserved / available）、"
                    + "inventory_policy（max_on_hand）；reporting / reporting_sales_trend_fact（quantity）",
                "",
                "⚠️ 库存读不到时该次运行**不生成库存结论**，不用 0 冒充「库存为 0」"),
            new AiDiagnosisCapability(
                "18.4.2.3",
                "利润异常归因（采购成本上涨 / 成交均价下跌 / 成本占比过高）",
                true,
                "trade / purchase_analytics_fact（fact_kind='COST' 的 unit_price / amount）；"
                    + "reporting / reporting_sales_trend_fact（value / quantity）",
                "",
                "⚠️ **不跨币种相加**：上游给了多币种时金额为 null ⇒ 该条规则不触发，"
                    + "并如实写明「拿不到单价」"),
            new AiDiagnosisCapability(
                "18.4.2.3",
                "发货延迟归因（SLA 超时 / 订单→出库慢 / 出库→交接慢 / 承诺未达成）",
                true,
                "trade / order_timeliness_fact（ship_sla_state / ship_overdue_seconds / "
                    + "order_to_pack_seconds / pack_to_handover_seconds / promise_vs_delivery_state）",
                "",
                "⚠️ 订单在时效事实里查不到时**不出结论**，运行记录写明「查不到该订单的时效事实」"),
            new AiDiagnosisCapability(
                "18.4.2.3",
                "同步失败归因（死信 / 永久失败 / 限流 / 鉴权失败 / 规则执行失败 / 超时 / 未执行）",
                true,
                "platform 自有事实表（**只读**）：platform_delivery_record / platform_api_call_log / "
                    + "platform_automation_rule_run",
                "",
                "⚠️ 这三张表**一个字都不改**（每张事实表只有一个写入所有者）"),
            new AiDiagnosisCapability(
                "18.4.2.3",
                "广告亏损归因（ACOS 超标 / ROAS 低于盈亏平衡 / 有花费无销售 / 点击率过低）",
                true,
                "channel / ad_campaign_daily_metric（acos / roas / spend / sales / ctr）"
                    + "—— 经 14.3.1.1 活动报表读出",
                "",
                "⚠️ ACOS / ROAS 口径**跟随上游**（先汇总再相除），本模块不另算一套"),
            new AiDiagnosisCapability(
                "18.4.2.3",
                "🔴 **AI 大模型推理 / 自动执行处置动作**",
                false,
                "本仓目前没有任何真实的 AI / LLM 供应商适配器"
                    + "（backend / services / tools 下不存在 openai / anthropic / qwen / deepseek 之类的客户端）",
                "18.4",
                "本模块**只给结论、依据与建议**：处置动作的执行归各自的域"
                    + "（补货归 6 采购、调价归 5 定价、停广告归 14 广告）"),
            new AiDiagnosisCapability(
                "18.4.2.3",
                "从**工作台待办**自动产生异常事件",
                false,
                "卷 01 工作台的待办表**不存在**（backend 下没有对应迁移）",
                "1",
                "⇒ 异常事件只能由调用方提交或从平台内部失败源聚合；"
                    + "本模块**不假装**能读工作台待办"));

    var gaps =
        List.of(
            new AiDiagnosisGap(
                "1",
                "工作台待办（异常事件的上游之一）",
                "platform",
                false,
                "上游",
                "蓝图关系句写「上游 = 1 工作台待办、各域异常」，但**这两张表都不存在** ⇒ "
                    + "本模块的异常事件来源只有「调用方提交 + 平台内部失败源同步」"),
            new AiDiagnosisGap(
                "13 / 14 / 16",
                "各域异常事件表",
                "reporting / channel / trade",
                false,
                "上游",
                "各域的异常事件表**不存在**；本模块改为**直接读它们的事实表**"
                    + "（销量趋势 / 广告指标 / 时效事实）来归因"),
            new AiDiagnosisGap(
                "18.4",
                "AI / LLM 供应商适配器",
                "platform",
                false,
                "卷级基础设施",
                "18.4 各模块共用的通道；本模块的归因是确定性的，不依赖它"),
            new AiDiagnosisGap(
                "6.2.1.2 / 5.4 / 14.3",
                "处置动作的**执行**",
                "trade / catalog / channel",
                false,
                "下游",
                "本模块只给**建议动作与指向的处理页**，**不执行**任何一个"
                    + "（跨服务写别人的库是被禁止的）"));

    var rules =
        List.of(
            // ---- 库存异常 ----
            new AiDiagnosisRule(
                "INVENTORY_STOCKOUT",
                "可用库存归零",
                "INVENTORY",
                "库存异常",
                "该 SKU 的任一库存行的 available ≤ 0",
                "warehouse / inventory_balance / available（快照时点读到的可用量）",
                "核对 7 库存明细里的可用量与库位状态；确认是真实断货还是全部被预占",
                "7 库存",
                true,
                "🔴 available 是**生成列**（on_hand − reserved），口径以库里的定义为准"),
            new AiDiagnosisRule(
                "INVENTORY_OVERSTOCK",
                "在手超过超储阈值",
                "INVENTORY",
                "库存异常",
                "该 SKU 的任一库存行 on_hand > max_on_hand（且已配置阈值）",
                "warehouse / inventory_policy / max_on_hand 与 warehouse / inventory_balance / on_hand",
                "按超储处理：暂停补货、安排调拨或清货",
                "7 库存",
                true,
                "⚠️ **未配置阈值**（thresholdConfigured=false）与「查过没超」是两件事，不混为一谈"),
            new AiDiagnosisRule(
                "INVENTORY_RESERVED_BLOCKED",
                "库存几乎全被预占",
                "INVENTORY",
                "库存异常",
                "该 SKU 的任一库存行 reserved ÷ on_hand ≥ 90%（且 on_hand > 0）",
                "warehouse / inventory_balance / reserved 与 / on_hand",
                "核对在途订单与预占超时：已取消的订单释放预占后可用量会回来",
                "9 订单 / 7 库存",
                true,
                "⚠️ on_hand = 0 时**不触发**（0 ÷ 0 无意义）"),
            new AiDiagnosisRule(
                "INVENTORY_SALES_SURGE",
                "销量突增",
                "INVENTORY",
                "库存异常",
                "近 7 天日均销量 ÷ 前 7 天日均销量 ≥ 1.5（且前段日均 > 0）",
                "reporting / reporting_sales_trend_fact / quantity（PRODUCT_SALES_QUANTITY）",
                "按突增量核对补货节奏；必要时提前下单",
                "6 采购 / 13 分析",
                true,
                "⚠️ 日均按**整条日历**算：没有记录的日子按 0 销量计"),
            // ---- 利润异常 ----
            new AiDiagnosisRule(
                "PROFIT_COST_SPIKE",
                "采购单价上涨",
                "PROFIT",
                "利润异常",
                "近 7 天采购加权均价 ÷ 前 7 天采购加权均价 ≥ 1.15（且前段均价 > 0）",
                "trade / purchase_analytics_fact / unit_price（fact_kind='COST'）",
                "复核采购价与头程报价；必要时换供应商或调整定价",
                "6 采购",
                true,
                "🔴 加权均价 = Σamount ÷ Σquantity；**不跨币种相加**"),
            new AiDiagnosisRule(
                "PROFIT_PRICE_DROP",
                "成交均价下跌",
                "PROFIT",
                "利润异常",
                "近 7 天成交均价 ÷ 前 7 天成交均价 ≤ 0.85（且前段均价 > 0）",
                "reporting / reporting_sales_trend_fact / value 与 / quantity（PRODUCT_SALES_AMOUNT）",
                "核对是否有促销或竞品降价；若为促销拉动的销量，判定是否值得",
                "5 定价 / 13 分析",
                true,
                "⚠️ 上游给了多币种时金额为 null ⇒ **不触发**，并写明「拿不到单价」"),
            new AiDiagnosisRule(
                "PROFIT_COST_RATIO_HIGH",
                "成本占销售额比例过高",
                "PROFIT",
                "利润异常",
                "近 7 天采购金额 ÷ 近 7 天销售额 ≥ 0.80（且销售额 > 0）",
                "trade / purchase_analytics_fact / amount 与 reporting / reporting_sales_trend_fact / value",
                "复核该 SKU 的成本结构；优先处理成本占比最高的 SKU",
                "6 采购 / 5 定价",
                true,
                "⚠️ **不跨币种相加**：销售额与采购金额必须同币种，否则这条不触发"),
            // ---- 发货延迟 ----
            new AiDiagnosisRule(
                "SHIPPING_SLA_OVERDUE",
                "发货 SLA 超时",
                "SHIPPING_DELAY",
                "发货延迟",
                "该订单的 ship_sla_state = 'OVERDUE' 且 ship_overdue_seconds > 0",
                "trade / order_timeliness_fact / ship_overdue_seconds 与 / ship_sla_state",
                "联系仓库催交；若整批超时则调整该仓的发货 SLA 承诺",
                "9 履约",
                true,
                "⚠️ SLA 状态是事实（不是本模块的判断）"),
            new AiDiagnosisRule(
                "SHIPPING_ORDER_TO_PACK_SLOW",
                "订单到出库慢",
                "SHIPPING_DELAY",
                "发货延迟",
                "该订单的 order_to_pack_seconds ≥ 86400（24 小时）",
                "trade / order_timeliness_fact / order_to_pack_seconds",
                "查拣货与库存分配环节；缺货时优先从有货仓调拨",
                "7 库存 / 9 履约",
                true,
                "⚠️ 未测出这一段的订单**不触发**（值缺失 ≠ 段耗时为 0）"),
            new AiDiagnosisRule(
                "SHIPPING_PACK_TO_HANDOVER_SLOW",
                "出库到交接慢",
                "SHIPPING_DELAY",
                "发货延迟",
                "该订单的 pack_to_handover_seconds ≥ 43200（12 小时）",
                "trade / order_timeliness_fact / pack_to_handover_seconds",
                "查承运商上门时效与仓库交接队列；必要时换承运商",
                "9 履约",
                true,
                "⚠️ 未测出这一段的订单**不触发**"),
            new AiDiagnosisRule(
                "SHIPPING_PROMISE_LATE",
                "承诺到货未达成",
                "SHIPPING_DELAY",
                "发货延迟",
                "该订单的 promise_vs_delivery_state = 'LATE'",
                "trade / order_timeliness_fact / promise_vs_delivery_state 与 / promise_vs_delivery_seconds",
                "主动联系买家并给出补救方案；累计发生则复核承诺时效配置",
                "9 履约 / 10 售后",
                true,
                "⚠️ 承诺到货时刻是**人工登记**的凭据（10.3 未交付），不是平台 SLA"),
            // ---- 同步失败 ----
            new AiDiagnosisRule(
                "SYNC_DELIVERY_DEAD_LETTER",
                "投递进了死信",
                "SYNC_FAILURE",
                "同步失败",
                "该订阅存在 status = 'DEAD_LETTER' 的投递记录",
                "platform / platform_delivery_record / status 与 / attempt_count 与 / last_error",
                "核对目标地址与签名密钥；修复后从死信箱重放",
                "18.1.2 事件订阅",
                true,
                "🔴 platform_delivery_record **只读**（V413 有自己的写入所有者）"),
            new AiDiagnosisRule(
                "SYNC_DELIVERY_FAILED",
                "投递永久失败",
                "SYNC_FAILURE",
                "同步失败",
                "该订阅存在 status = 'FAILED' 的投递记录（不会自动重试）",
                "platform / platform_delivery_record / status 与 / status_code 与 / last_error",
                "按 last_error 定位（地址非法 / 订阅停用 / 无生效签名密钥）并修复后重放",
                "18.1.2 事件订阅",
                true,
                "⚠️ FAILED 与 DEAD_LETTER 是**两件事**：前者不会自动重试，后者是重试用尽"),
            new AiDiagnosisRule(
                "SYNC_API_RATE_LIMITED",
                "接口调用被限流",
                "SYNC_FAILURE",
                "同步失败",
                "该接口存在 rate_limited = true 的调用记录",
                "platform / platform_api_call_log / rate_limited 与 / status_code 与 / called_at",
                "按 18.1.1.1 调整该应用的配额窗口；必要时降低调用并发",
                "18.1.1 应用与配额",
                true,
                "🔴 429 既是「限流命中」也是「一次错误」，两列分开记（V414 同口径）"),
            new AiDiagnosisRule(
                "SYNC_API_AUTH_FAILED",
                "接口调用鉴权失败",
                "SYNC_FAILURE",
                "同步失败",
                "该接口存在 outcome = 'AUTH_FAILED' 的调用记录",
                "platform / platform_api_call_log / outcome 与 / error_code",
                "核对凭证是否过期 / SECRET 不匹配 / IP 白名单是否放行",
                "18.1.1 应用与配额",
                true,
                "⚠️ error_code 细到 SECRET_MISMATCH / IP_NOT_ALLOWED / SCOPE_DENIED / QUOTA_EXCEEDED"),
            new AiDiagnosisRule(
                "SYNC_RULE_RUN_FAILED",
                "规则执行失败",
                "SYNC_FAILURE",
                "同步失败",
                "该规则存在 outcome = 'FAILED' 的执行记录",
                "platform / platform_automation_rule_run / outcome 与 / failure_class 与 / error_message",
                "按 failure_class 定位（ACTION_FAILED / ENGINE_ERROR / …）；动作本身的失败归动作所属域",
                "18.3 自动化",
                true,
                "⚠️ platform_automation_rule_run **只读**（V422 有自己的写入所有者）"),
            new AiDiagnosisRule(
                "SYNC_RULE_RUN_TIMEOUT",
                "规则执行超时",
                "SYNC_FAILURE",
                "同步失败",
                "该规则存在 outcome = 'TIMEOUT' 的执行记录",
                "platform / platform_automation_rule_run / outcome 与 / duration_ms",
                "看该规则的触发体量；必要时拆分动作或调高超时",
                "18.3 自动化",
                true,
                "⚠️ duration_ms 是**整数毫秒**（不是浮点）"),
            new AiDiagnosisRule(
                "SYNC_RULE_RUN_NOT_ATTEMPTED",
                "规则未执行（执行器未接通）",
                "SYNC_FAILURE",
                "同步失败",
                "该规则存在 outcome = 'NOT_ATTEMPTED' 的执行记录",
                "platform / platform_automation_rule_run / outcome 与 / not_attempted_reason",
                "这是**能力缺口**而不是故障：等 18.3.2 的执行器接通后重跑",
                "18.3 自动化",
                true,
                "🔴 与「失败」分开报 —— NOT_ATTEMPTED 不该被当成故障去排查"),
            // ---- 广告亏损 ----
            new AiDiagnosisRule(
                "AD_ACOS_ABOVE_TARGET",
                "ACOS 超过阈值",
                "AD_LOSS",
                "广告亏损",
                "该活动窗口内的 acos ≥ 0.30",
                "channel / ad_campaign_daily_metric / acos 与 / spend 与 / sales",
                "降竞价或收窄投放词；核对商品毛利后决定是否继续投放",
                "14 广告",
                true,
                "🔴 ACOS = Σ花费 ÷ Σ销售额（**先汇总再相除**），口径来自上游"),
            new AiDiagnosisRule(
                "AD_ROAS_BELOW_BREAKEVEN",
                "ROAS 低于盈亏平衡",
                "AD_LOSS",
                "广告亏损",
                "该活动窗口内的 roas < 1.00",
                "channel / ad_campaign_daily_metric / roas 与 / spend 与 / sales",
                "核对销售额统计口径是否包含该平台的订单；若口径没问题则降价或停投",
                "14 广告",
                true,
                "⚠️ roas 分母为 0 时上游给 null（不可比）⇒ **不触发**"),
            new AiDiagnosisRule(
                "AD_SPEND_WITHOUT_SALES",
                "有花费没有销售额",
                "AD_LOSS",
                "广告亏损",
                "该活动窗口内 sales ≤ 0 且 spend > 0",
                "channel / ad_campaign_daily_metric / spend 与 / sales",
                "核对投放是否跑错页面 / 库存是否已断；断货时先补货再投",
                "14 广告 / 7 库存",
                true,
                "⚠️ 「没销售额」可能来自同步未完成 ⇒ 同时看上游的数据截至时刻"),
            new AiDiagnosisRule(
                "AD_LOW_CTR",
                "点击率过低",
                "AD_LOSS",
                "广告亏损",
                "该活动窗口内的 ctr < 0.005（0.5%）",
                "channel / ad_campaign_daily_metric / ctr 与 / clicks 与 / impressions",
                "换素材或换定向；先看曝光量是否足够，曝光太少时 CTR 不可比",
                "14 广告",
                true,
                "⚠️ impressions = 0 时上游给 null（不可比）⇒ **不触发**"));

    var thresholds =
        List.of(
            new AiDiagnosisThreshold(
                "RESERVED_RATIO", "预占占在手比例阈值", RESERVED_RATIO_THRESHOLD, "比例",
                "reserved ÷ on_hand 达到这个比例即判定「库存被占住」"),
            new AiDiagnosisThreshold(
                "SALES_SURGE_RATIO", "销量突增倍数", SALES_SURGE_RATIO, "倍",
                "近 7 天日均 ÷ 前 7 天日均"),
            new AiDiagnosisThreshold(
                "COST_SPIKE_RATIO", "采购单价上涨阈值", COST_SPIKE_RATIO, "倍",
                "近 7 天加权均价 ÷ 前 7 天加权均价（1.15 = 涨 15%）"),
            new AiDiagnosisThreshold(
                "PRICE_DROP_RATIO", "成交均价下跌阈值", PRICE_DROP_RATIO, "倍",
                "近 7 天均价 ÷ 前 7 天均价（0.85 = 跌 15%）"),
            new AiDiagnosisThreshold(
                "COST_RATIO", "成本占销售额比阈值", COST_RATIO_THRESHOLD, "比例",
                "近 7 天采购金额 ÷ 近 7 天销售额"),
            new AiDiagnosisThreshold(
                "ORDER_TO_PACK_SECONDS", "订单到出库耗时阈值",
                BigDecimal.valueOf(ORDER_TO_PACK_THRESHOLD_SECONDS), "秒", "24 小时"),
            new AiDiagnosisThreshold(
                "PACK_TO_HANDOVER_SECONDS", "出库到交接耗时阈值",
                BigDecimal.valueOf(PACK_TO_HANDOVER_THRESHOLD_SECONDS), "秒", "12 小时"),
            new AiDiagnosisThreshold(
                "ACOS", "ACOS 阈值", ACOS_THRESHOLD, "比例", "Σ花费 ÷ Σ销售额"),
            new AiDiagnosisThreshold(
                "ROAS_BREAKEVEN", "ROAS 盈亏平衡", ROAS_BREAKEVEN, "倍", "Σ销售额 ÷ Σ花费"),
            new AiDiagnosisThreshold(
                "CTR", "CTR 阈值", CTR_THRESHOLD, "比例", "Σ点击 ÷ Σ曝光"),
            new AiDiagnosisThreshold(
                "WINDOW_DAYS", "归因窗口", BigDecimal.valueOf(RECENT_WINDOW_DAYS + PRIOR_WINDOW_DAYS),
                "天", "近段 7 天 + 前段 7 天；两段不重叠"),
            new AiDiagnosisThreshold(
                "RULE_VERSION", "规则集版本", new BigDecimal("1"), "版本",
                "随每次运行落库，事后能说清「当时按哪一版判的」"));

    var sources =
        List.of(
            new AiDiagnosisSource(
                "WAREHOUSE_INVENTORY",
                "库存头寸（在手 / 预占 / 可用 / 超储阈值）",
                "warehouse（7 库存）",
                true,
                "GET /warehouse/inventory/overview",
                "",
                "⚠️ 读不到时该次运行**不生成库存结论**（不用 0 冒充「库存为 0」）"),
            new AiDiagnosisSource(
                "REPORTING_SALES",
                "商品维度销量与成交金额（按 SKU 码分组）",
                "reporting（13 分析）",
                true,
                "GET /reporting/sales-trend?granularity=DAY"
                    + "&metricCodes=PRODUCT_SALES_QUANTITY,PRODUCT_SALES_AMOUNT",
                "",
                "⚠️ 金额按币种分列 ⇒ **多币种时不跨币种相加**，单价为 null 时相关规则不触发"),
            new AiDiagnosisSource(
                "TRADE_PURCHASE_COST",
                "采购成本事实（fact_kind='COST' 的单价与金额）",
                "trade（6 采购）",
                true,
                "GET /trade/purchase-analytics/facts?factKind=COST",
                "",
                "🔴 加权均价 = Σamount ÷ Σquantity；**不跨币种相加**"),
            new AiDiagnosisSource(
                "TRADE_TIMELINESS",
                "发货时效事实（逐段耗时 / 发货 SLA / 承诺到货）",
                "trade（9 履约）",
                true,
                "GET /trade/fulfillment/timeliness/facts",
                "",
                "⚠️ 查不到该订单时**不出结论**，运行记录写明「查不到该订单的时效事实」"),
            new AiDiagnosisSource(
                "CHANNEL_AD_REPORT",
                "活动日报表（花费 / 销售额 / ACOS / ROAS / CTR）",
                "channel（14 广告）",
                true,
                "GET /channel/ad-reports/rows?dimension=CAMPAIGN",
                "",
                "🔴 ACOS / ROAS 口径**跟随上游**（先汇总再相除）"),
            new AiDiagnosisSource(
                "PLATFORM_INTERNAL_FAILURES",
                "平台内部失败源（投递记录 / 调用监控 / 规则执行日志）",
                "platform（18 集成开放）",
                true,
                "platform_delivery_record（V413）/ platform_api_call_log（V414）"
                    + " / platform_automation_rule_run（V422）",
                "",
                "🔴 这三张表**只读**（各有自己的写入所有者）"),
            new AiDiagnosisSource(
                "WORKBENCH_TODO",
                "工作台待办（异常事件的上游之一）",
                "platform（卷 01）",
                false,
                "（无 —— 待办表不存在）",
                "1",
                "🔴 **不存在**：异常事件只能由调用方提交或从平台内部失败源聚合"),
            new AiDiagnosisSource(
                "AI_MODEL",
                "AI / LLM 推理通道",
                "platform（18.4 卷级）",
                false,
                "（无）",
                "18.4",
                "🔴 本仓没有任何真实的 AI / LLM 供应商适配器 ⇒ 本模块做**确定性归因**，"
                    + "**不假装**有模型在跑"));

    return new AiDiagnosisCapabilities(capabilities, gaps, rules, thresholds, sources);
  }

  /** 概览：几个数。 */
  public AiDiagnosisOverview overview() {
    var byType =
        sql.fetch("select anomaly_type, count(*) as c from platform_diagnosis_event group by anomaly_type")
            .map(
                r ->
                    new AiDiagnosisAnomalyCount(
                        r.get("anomaly_type", String.class),
                        anomalyLabel(r.get("anomaly_type", String.class)),
                        r.get("c", Long.class)));
    return new AiDiagnosisOverview(
        count("select count(*) from platform_diagnosis_event"),
        count("select count(*) from platform_diagnosis_event where status = 'OPEN'"),
        count("select count(*) from platform_diagnosis_event where status = 'DIAGNOSED'"),
        count("select count(*) from platform_diagnosis_event where status = 'RESOLVED'"),
        count("select count(*) from platform_diagnosis_run"),
        count("select count(*) from platform_diagnosis_conclusion"),
        count("select count(*) from platform_diagnosis_evidence"),
        byType,
        OffsetDateTime.now(ZoneOffset.UTC));
  }

  // ================================================================ 异常事件

  /** 异常事件列表（真分页 + 真筛选 + 真搜索）。 */
  public AiDiagnosisEventPage events(
      String anomalyType, String status, String keyword, int page, int size) {
    var where = new StringBuilder(" where 1 = 1");
    var params = new ArrayList<Object>();
    if (anomalyType != null && !anomalyType.isBlank()) {
      where.append(" and anomaly_type = ?");
      params.add(anomalyType.trim().toUpperCase(Locale.ROOT));
    }
    if (status != null && !status.isBlank()) {
      where.append(" and status = ?");
      params.add(status.trim().toUpperCase(Locale.ROOT));
    }
    if (keyword != null && !keyword.isBlank()) {
      var k = "%" + keyword.trim() + "%";
      where.append(
          " and (event_no ilike ? or subject_ref ilike ? or subject_label ilike ?"
              + " or note ilike ? or source_ref ilike ?)");
      params.add(k);
      params.add(k);
      params.add(k);
      params.add(k);
      params.add(k);
    }
    var total =
        count("select count(*) from platform_diagnosis_event" + where, params.toArray());
    var current = Math.max(page, 0);
    var limit = pageSize(size);
    var rows =
        sql.fetch(
            "select id,event_no,anomaly_type,severity,subject_type,subject_ref,subject_label,"
                + " occurred_at,detected_at,source,source_ref,reported_value,reported_unit,status,note,"
                + " created_at from platform_diagnosis_event"
                + where
                + " order by occurred_at desc, id desc limit ? offset ?",
            concat(params.toArray(), limit, (long) current * limit));
    return new AiDiagnosisEventPage(
        rows.map(AiDiagnosisService::toEventRow), current, limit, total, pages(total, limit));
  }

  /**
   * 登记一个异常事件（**调用方提交**这一路来源）。
   *
   * <p>🔴 请求体**不接受 tenantId**：租户从已认证成员关系解析。
   */
  public AiDiagnosisEventRow submit(AiDiagnosisEventInput input, UUID actorId) {
    var anomalyType = required(input.anomalyType(), "异常类别", 32).toUpperCase(Locale.ROOT);
    check(
        List.of("INVENTORY", "PROFIT", "SHIPPING_DELAY", "SYNC_FAILURE", "AD_LOSS")
            .contains(anomalyType),
        "异常类别只能是 INVENTORY / PROFIT / SHIPPING_DELAY / SYNC_FAILURE / AD_LOSS");
    var subjectType = required(input.subjectType(), "异常对象类型", 32).toUpperCase(Locale.ROOT);
    check(
        List.of("SKU", "ORDER", "CAMPAIGN", "SUBSCRIPTION", "APP", "ENDPOINT", "RULE")
            .contains(subjectType),
        "异常对象类型只能是 SKU / ORDER / CAMPAIGN / SUBSCRIPTION / APP / ENDPOINT / RULE");
    var subjectRef = required(input.subjectRef(), "异常对象", 200);
    var subjectLabel = optional(input.subjectLabel(), "异常对象名称", 200);
    var severity = optional(input.severity(), "严重度", 16).toUpperCase(Locale.ROOT);
    if (severity.isBlank()) severity = "WARNING";
    check(
        List.of("INFO", "WARNING", "CRITICAL").contains(severity),
        "严重度只能是 INFO / WARNING / CRITICAL");
    var unit = optional(input.reportedUnit(), "度量单位", 24);
    var value = input.reportedValue();
    check(value == null || value.signum() >= 0, "度量值不能为负");
    var note = optional(input.note(), "备注", 500);
    var occurredAt =
        input.occurredAt() == null ? OffsetDateTime.now(ZoneOffset.UTC) : input.occurredAt();

    var id = UUID.randomUUID();
    var tenant = currentTenant();
    var seq = sql.fetchOne("select nextval('platform_diagnosis_event_no_seq') as seq").get("seq", Long.class);
    var eventNo = "EVT-" + tenant.toString().substring(0, 8) + "-" + String.format("%06d", seq);
    sql.execute(
        "insert into platform_diagnosis_event(id,tenant_id,event_no,anomaly_type,subject_type,"
            + " subject_ref,subject_label,occurred_at,detected_at,source,source_ref,reported_value,"
            + " reported_unit,severity,status,note,created_by,created_at,updated_at)"
            + " values(?,?,?,?,?,?,?,?::timestamptz,?::timestamptz,'SUBMITTED','',?,?,?,'OPEN',?,"
            + " ?,?::timestamptz,?::timestamptz)",
        id,
        tenant,
        eventNo,
        anomalyType,
        subjectType,
        subjectRef,
        subjectLabel,
        occurredAt,
        OffsetDateTime.now(ZoneOffset.UTC),
        value,
        unit,
        severity,
        note,
        actorId,
        OffsetDateTime.now(ZoneOffset.UTC),
        OffsetDateTime.now(ZoneOffset.UTC));
    return eventRow(id);
  }

  /** 人工标记异常事件已处置（**只记人的决定**，本模块不执行任何下游动作）。 */
  public AiDiagnosisEventRow resolve(UUID id, AiDiagnosisResolveInput input) {
    var row = sql.fetchOne("select status from platform_diagnosis_event where id = ?", id);
    if (row == null) notFound("异常事件不存在");
    var current = row.get("status", String.class);
    if ("RESOLVED".equals(current)) {
      conflict("该异常事件已经是「已处置」状态了");
    }
    var note = required(input == null ? null : input.note(), "处置说明", 500);
    sql.execute(
        "update platform_diagnosis_event set status = 'RESOLVED', note = ?,"
            + " updated_at = ?::timestamptz where id = ?",
        note,
        OffsetDateTime.now(ZoneOffset.UTC),
        id);
    return eventRow(id);
  }

  /**
   * 事件详情：事件 + 它的历史运行（**不含**最近一次运行的结论与依据）。
   *
   * <p>🔴 结论与依据走**运行详情**（{@code GET /platform/ai/diagnosis/runs/{id}}）：
   * 「还没跑过诊断」与「跑了但没命中规则」是两件事，摊平成两个端点后，
   * 前端用 {@code runs} 是否为空就能分清，不依赖一个可能为 null 的嵌套对象。
   */
  public AiDiagnosisEventDetail eventDetail(UUID id) {
    var event = eventRow(id);
    var runs =
        sql.fetch(
                "select id,run_no,event_id,status,rule_version,conclusion_count,evidence_count,"
                    + " sources_read,sources_unavailable,note,requested_by,started_at,finished_at"
                    + " from platform_diagnosis_run where event_id = ? order by started_at desc",
                id)
            .map(AiDiagnosisService::toRunRow);
    return new AiDiagnosisEventDetail(event, runs);
  }

  private AiDiagnosisEventRow eventRow(UUID id) {
    var row =
        sql.fetchOne(
            "select id,event_no,anomaly_type,severity,subject_type,subject_ref,subject_label,"
                + " occurred_at,detected_at,source,source_ref,reported_value,reported_unit,status,"
                + " note,created_at from platform_diagnosis_event where id = ?",
            id);
    if (row == null) notFound("异常事件不存在");
    return toEventRow(row);
  }

  private static AiDiagnosisEventRow toEventRow(org.jooq.Record r) {
    return new AiDiagnosisEventRow(
        r.get("id", UUID.class),
        r.get("event_no", String.class),
        r.get("anomaly_type", String.class),
        anomalyLabel(r.get("anomaly_type", String.class)),
        r.get("severity", String.class),
        r.get("subject_type", String.class),
        r.get("subject_ref", String.class),
        r.get("subject_label", String.class),
        r.get("occurred_at", OffsetDateTime.class),
        r.get("detected_at", OffsetDateTime.class),
        r.get("source", String.class),
        r.get("source_ref", String.class),
        r.get("reported_value", BigDecimal.class),
        r.get("reported_unit", String.class),
        r.get("status", String.class),
        r.get("note", String.class),
        r.get("created_at", OffsetDateTime.class));
  }

  // ================================================================ 平台内部失败源同步

  /**
   * 从**平台自己的失败源**聚合异常事件。
   *
   * <p>🔴 **只读上游**：V413 / V414 / V422 三张事实表一个字都不改。
   * <p>🔴 **幂等**：同一个失败源事实只登记一次（源表 + 行 id 做幂等键）——
   * 重复同步不会把同一件事刷成几十行。
   */
  public AiDiagnosisSyncResult syncInternalFailures(UUID actorId) {
    var tenant = currentTenant();
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    var scanned = 0;
    var created = 0;
    var skipped = 0;
    var sources = List.of("platform_delivery_record", "platform_api_call_log", "platform_automation_rule_run");

    // ---- 1. 投递记录（死信 / 永久失败）----
    var deliveries =
        sql.fetch(
            "select id,subscription_id,event_type,status,status_code,attempt_count,last_error,"
                + " created_at from platform_delivery_record"
                + " where status in ('DEAD_LETTER','FAILED') order by created_at desc limit ?",
            SYNC_SCAN_LIMIT);
    for (var r : deliveries) {
      scanned++;
      var status = r.get("status", String.class);
      var severity = "DEAD_LETTER".equals(status) ? "CRITICAL" : "WARNING";
      var attemptCount = r.get("attempt_count", Integer.class);
      var lastError = r.get("last_error", String.class);
      var note =
          "投递记录 " + r.get("id", UUID.class) + "（事件类型 " + r.get("event_type", String.class)
              + "）状态 " + status + "，最近错误：" + (lastError.isBlank() ? "（上游未记录）" : lastError);
      var made =
          insertInternalEvent(
              tenant,
              "SYNC_FAILURE",
              "SUBSCRIPTION",
              r.get("subscription_id", UUID.class).toString(),
              r.get("event_type", String.class),
              "platform_delivery_record:" + r.get("id", UUID.class),
              severity,
              attemptCount == null ? null : BigDecimal.valueOf(attemptCount),
              "次",
              note,
              now,
              actorId);
      if (made) created++;
      else skipped++;
    }

    // ---- 2. 接口调用（限流 / 鉴权失败）----
    var apiCalls =
        sql.fetch(
            "select id,endpoint_code,http_method,status_code,outcome,error_code,rate_limited,"
                + " called_at from platform_api_call_log"
                + " where outcome in ('RATE_LIMITED','AUTH_FAILED')"
                + " order by called_at desc limit ?",
            SYNC_SCAN_LIMIT);
    for (var r : apiCalls) {
      scanned++;
      var outcome = r.get("outcome", String.class);
      var severity = "AUTH_FAILED".equals(outcome) ? "CRITICAL" : "WARNING";
      var errorCode = r.get("error_code", String.class);
      var note =
          "调用记录 " + r.get("id", UUID.class) + "（" + r.get("http_method", String.class) + " "
              + r.get("endpoint_code", String.class) + "）结论 " + outcome
              + "，状态码 " + r.get("status_code", Integer.class)
              + "，错误码：" + (errorCode.isBlank() ? "（上游未记录）" : errorCode);
      var made =
          insertInternalEvent(
              tenant,
              "SYNC_FAILURE",
              "ENDPOINT",
              r.get("endpoint_code", String.class),
              r.get("endpoint_code", String.class),
              "platform_api_call_log:" + r.get("id", UUID.class),
              severity,
              BigDecimal.valueOf(r.get("status_code", Integer.class)),
              "HTTP 状态码",
              note,
              now,
              actorId);
      if (made) created++;
      else skipped++;
    }

    // ---- 3. 规则执行（失败 / 超时 / 未执行）----
    var ruleRuns =
        sql.fetch(
            "select id,rule_id,rule_name,outcome,failure_class,error_code,error_message,"
                + " duration_ms,started_at from platform_automation_rule_run"
                + " where outcome in ('FAILED','TIMEOUT','NOT_ATTEMPTED')"
                + " order by started_at desc limit ?",
            SYNC_SCAN_LIMIT);
    for (var r : ruleRuns) {
      scanned++;
      var outcome = r.get("outcome", String.class);
      var severity = "NOT_ATTEMPTED".equals(outcome) ? "INFO" : "WARNING";
      var failureClass = r.get("failure_class", String.class);
      var errorMessage = r.get("error_message", String.class);
      var note =
          "执行记录 " + r.get("id", UUID.class) + "（规则 " + r.get("rule_name", String.class)
              + "）结论 " + outcome + "，失败分类：" + (failureClass.isBlank() ? "（无）" : failureClass)
              + "，错误信息：" + (errorMessage.isBlank() ? "（上游未记录）" : errorMessage);
      var made =
          insertInternalEvent(
              tenant,
              "SYNC_FAILURE",
              "RULE",
              r.get("rule_id", UUID.class).toString(),
              r.get("rule_name", String.class),
              "platform_automation_rule_run:" + r.get("id", UUID.class),
              severity,
              BigDecimal.valueOf(r.get("duration_ms", Long.class)),
              "毫秒",
              note,
              now,
              actorId);
      if (made) created++;
      else skipped++;
    }

    var note =
        "扫描 " + scanned + " 条平台内部失败源事实；新登记 " + created + " 个异常事件；跳过 " + skipped
            + " 个（已登记过，幂等不刷屏）";
    return new AiDiagnosisSyncResult(scanned, created, skipped, sources, note);
  }

  /**
   * 登记一个「平台内部失败源」异常事件。
   *
   * @return true = 新登记；false = 已登记过（幂等跳过）
   */
  private boolean insertInternalEvent(
      UUID tenant,
      String anomalyType,
      String subjectType,
      String subjectRef,
      String subjectLabel,
      String sourceRef,
      String severity,
      BigDecimal reportedValue,
      String reportedUnit,
      String note,
      OffsetDateTime now,
      UUID actorId) {
    var exists =
        sql.fetchOne(
                "select id from platform_diagnosis_event where tenant_id = ? and source ="
                    + " 'PLATFORM_INTERNAL' and source_ref = ?",
                tenant,
                sourceRef)
            != null;
    if (exists) return false;
    var id = UUID.randomUUID();
    var seq = sql.fetchOne("select nextval('platform_diagnosis_event_no_seq') as seq").get("seq", Long.class);
    var eventNo = "EVT-" + tenant.toString().substring(0, 8) + "-" + String.format("%06d", seq);
    sql.execute(
        "insert into platform_diagnosis_event(id,tenant_id,event_no,anomaly_type,subject_type,"
            + " subject_ref,subject_label,occurred_at,detected_at,source,source_ref,reported_value,"
            + " reported_unit,severity,status,note,created_by,created_at,updated_at)"
            + " values(?,?,?,?,?,?,?,?::timestamptz,?::timestamptz,'PLATFORM_INTERNAL',?,?,?,?,"
            + "'OPEN',?,?,?::timestamptz,?::timestamptz)"
            + " on conflict (tenant_id, source, source_ref) where source = 'PLATFORM_INTERNAL'"
            + " and source_ref <> '' do nothing",
        id,
        tenant,
        eventNo,
        anomalyType,
        subjectType,
        subjectRef,
        subjectLabel,
        now,
        now,
        sourceRef,
        reportedValue,
        reportedUnit,
        severity,
        note,
        actorId,
        now,
        now);
    return true;
  }

  // ================================================================ 诊断运行

  /**
   * 对一个异常事件发起一次诊断（**确定性归因**）。
   *
   * <p>🔴 **上游读不到就 503**：端口把上游故障翻成 503，本方法**不吞**——
   *   一个「看起来诊断成功但其实没读数据」的返回会让人以为已经查过了。
   */
  public AiDiagnosisRunDetail diagnose(UUID eventId, UUID actorId, String bearer) {
    var event = eventRow(eventId);
    var tenant = currentTenant();
    var now = OffsetDateTime.now(ZoneOffset.UTC);

    // 🔴 **先分析、后落库**：上游读不到时端口会翻成 503 ⇒ 整个事务回滚，
    //   一个字都不写。宁可没有记录，也不要一条「看起来诊断过」的记录。
    var ctx = new RunContext();
    switch (event.anomalyType()) {
      case "INVENTORY" -> diagnoseInventory(event, ctx, bearer);
      case "PROFIT" -> diagnoseProfit(event, ctx, bearer);
      case "SHIPPING_DELAY" -> diagnoseShippingDelay(event, ctx, bearer);
      case "SYNC_FAILURE" -> diagnoseSyncFailure(event, ctx);
      case "AD_LOSS" -> diagnoseAdLoss(event, ctx, bearer);
      default -> ctx.markUnavailable("engine", "没有匹配该异常类别的归因规则");
    }

    var seq = sql.fetchOne("select nextval('platform_diagnosis_run_no_seq') as seq").get("seq", Long.class);
    var runNo = "DG-" + tenant.toString().substring(0, 8) + "-" + String.format("%06d", seq);
    var runId = UUID.randomUUID();

    var conclusionCount = ctx.findings.size();
    // 🔴 **依据条数在写库之前就算得出来**（依据已在内存里组装好）——
    //   运行行必须**先于**结论与依据落库（结论对运行有外键 run_id），
    //   所以计数不能等到插完才知道。
    var evidenceCount = ctx.findings.stream().mapToInt(f -> f.evidences().size()).sum();
    var note =
        conclusionCount == 0
            ? "本次运行**没有命中任何归因规则**"
                + (ctx.unavailable.isEmpty()
                    ? "（读到的事实都不满足触发条件）"
                    : "；读不到的事实源：" + String.join("、", ctx.unavailable))
            : "命中 " + conclusionCount + " 条归因规则，产出 " + evidenceCount + " 条依据";

    // 🔴 **运行行先落库**：platform_diagnosis_conclusion 与 platform_diagnosis_evidence
    //   都对 platform_diagnosis_run 有外键（tenant_id, run_id）⇒ 运行不存在时，
    //   插结论会直接撞外键（一次真实的 409），不是「先插结论再补运行」能绕过去的。
    sql.execute(
        "insert into platform_diagnosis_run(id,tenant_id,run_no,event_id,status,rule_version,"
            + " conclusion_count,evidence_count,sources_read,sources_unavailable,note,requested_by,"
            + " started_at,finished_at) values(?,?,?,?,'COMPLETED',?,?,?,?,?,?,?,?::timestamptz,"
            + "?::timestamptz)",
        runId,
        tenant,
        runNo,
        eventId,
        RULE_VERSION,
        conclusionCount,
        evidenceCount,
        String.join(",", ctx.read),
        String.join(";", ctx.unavailable),
        note,
        actorId,
        now,
        OffsetDateTime.now(ZoneOffset.UTC));

    for (var finding : ctx.findings) {
      var conclusionId = UUID.randomUUID();
      sql.execute(
          "insert into platform_diagnosis_conclusion(id,tenant_id,run_id,event_id,rule_code,"
              + " rule_label,anomaly_type,severity,subject_ref,summary,detail,evidence_level,impact,"
              + " impact_unit,impact_text,recommendation,action_ref,action_owner,created_at)"
              + " values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::timestamptz)",
          conclusionId,
          tenant,
          runId,
          eventId,
          finding.ruleCode(),
          finding.ruleLabel(),
          event.anomalyType(),
          finding.severity(),
          event.subjectRef(),
          finding.summary(),
          finding.detail(),
          finding.evidences().size() >= 2 ? "STRONG" : "WEAK",
          finding.impact(),
          finding.impactUnit(),
          finding.impactText(),
          finding.recommendation(),
          finding.actionRef(),
          finding.actionOwner(),
          OffsetDateTime.now(ZoneOffset.UTC));
      for (var e : finding.evidences()) {
        sql.execute(
            "insert into platform_diagnosis_evidence(id,tenant_id,conclusion_id,run_id,event_id,"
                + " fact_service,fact_table,fact_field,fact_value,fact_value_text,fact_unit,"
                + " fact_ref,fact_occurred_at,snapshot_at,explanation,created_at)"
                + " values(?,?,?,?,?,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz,?,?::timestamptz)",
            UUID.randomUUID(),
            tenant,
            conclusionId,
            runId,
            eventId,
            e.factService(),
            e.factTable(),
            e.factField(),
            e.factValue(),
            e.factValueText(),
            e.factUnit(),
            e.factRef(),
            e.factOccurredAt(),
            OffsetDateTime.now(ZoneOffset.UTC),
            e.explanation(),
            OffsetDateTime.now(ZoneOffset.UTC));
      }
    }

    sql.execute(
        "update platform_diagnosis_event set status = case when status = 'OPEN' then 'DIAGNOSED'"
            + " else status end, updated_at = ?::timestamptz where id = ?",
        OffsetDateTime.now(ZoneOffset.UTC),
        eventId);
    return runDetail(runId);
  }

  /** 运行详情（运行 + 全部结论与依据）。 */
  public AiDiagnosisRunDetail runDetail(UUID id) {
    var runRow =
        sql.fetchOne(
            "select id,run_no,event_id,status,rule_version,conclusion_count,evidence_count,"
                + " sources_read,sources_unavailable,note,requested_by,started_at,finished_at"
                + " from platform_diagnosis_run where id = ?",
            id);
    if (runRow == null) notFound("诊断运行不存在");
    var run = toRunRow(runRow);
    var conclusions =
        sql.fetch(
                "select id,run_id,event_id,rule_code,rule_label,anomaly_type,severity,subject_ref,"
                    + " summary,detail,evidence_level,impact,impact_unit,impact_text,recommendation,"
                    + " action_ref,action_owner,created_at from platform_diagnosis_conclusion"
                    + " where run_id = ? order by severity desc, rule_code",
                id)
            .map(
                r -> {
                  var conclusionId = r.get("id", UUID.class);
                  var evidences =
                      sql.fetch(
                              "select id,conclusion_id,fact_service,fact_table,fact_field,fact_value,"
                                  + " fact_value_text,fact_unit,fact_ref,fact_occurred_at,snapshot_at,"
                                  + " explanation,created_at from platform_diagnosis_evidence"
                                  + " where conclusion_id = ? order by fact_table, fact_field",
                              conclusionId)
                          .map(AiDiagnosisService::toEvidenceRow);
                  return new AiDiagnosisConclusionDetail(
                      toConclusionRow(r), evidences);
                });
    return new AiDiagnosisRunDetail(run, conclusions);
  }

  private static AiDiagnosisRunRow toRunRow(org.jooq.Record r) {
    return new AiDiagnosisRunRow(
        r.get("id", UUID.class),
        r.get("run_no", String.class),
        r.get("event_id", UUID.class),
        r.get("status", String.class),
        r.get("rule_version", String.class),
        r.get("conclusion_count", Integer.class),
        r.get("evidence_count", Integer.class),
        r.get("sources_read", String.class),
        r.get("sources_unavailable", String.class),
        r.get("note", String.class),
        r.get("requested_by", UUID.class),
        r.get("started_at", OffsetDateTime.class),
        r.get("finished_at", OffsetDateTime.class));
  }

  private static AiDiagnosisConclusionRow toConclusionRow(org.jooq.Record r) {
    return new AiDiagnosisConclusionRow(
        r.get("id", UUID.class),
        r.get("run_id", UUID.class),
        r.get("event_id", UUID.class),
        r.get("rule_code", String.class),
        r.get("rule_label", String.class),
        r.get("anomaly_type", String.class),
        r.get("severity", String.class),
        r.get("subject_ref", String.class),
        r.get("summary", String.class),
        r.get("detail", String.class),
        r.get("evidence_level", String.class),
        r.get("impact", BigDecimal.class),
        r.get("impact_unit", String.class),
        r.get("impact_text", String.class),
        r.get("recommendation", String.class),
        r.get("action_ref", String.class),
        r.get("action_owner", String.class),
        r.get("created_at", OffsetDateTime.class));
  }

  private static AiDiagnosisEvidenceRow toEvidenceRow(org.jooq.Record r) {
    return new AiDiagnosisEvidenceRow(
        r.get("id", UUID.class),
        r.get("conclusion_id", UUID.class),
        r.get("fact_service", String.class),
        r.get("fact_table", String.class),
        r.get("fact_field", String.class),
        r.get("fact_value", BigDecimal.class),
        r.get("fact_value_text", String.class),
        r.get("fact_unit", String.class),
        r.get("fact_ref", String.class),
        r.get("fact_occurred_at", OffsetDateTime.class),
        r.get("snapshot_at", OffsetDateTime.class),
        r.get("explanation", String.class),
        r.get("created_at", OffsetDateTime.class));
  }

  // ================================================================ 归因引擎 · 库存异常

  /**
   * 库存异常归因。
   *
   * <p>事实源：warehouse 的 {@code inventory_balance} / {@code inventory_policy}
   * （经 {@code GET /warehouse/inventory/overview} 读出）+
   * reporting 的 {@code reporting_sales_trend_fact}（销量突增）。
   */
  private void diagnoseInventory(
      AiDiagnosisEventRow event, RunContext ctx, String bearer) {
    var sku = event.subjectRef();
    var balances = fetchBalances(bearer);
    ctx.markRead("warehouse");
    var mine =
        balances.stream()
            .filter(b -> sku.equals(b.skuCode()))
            .toList();
    if (mine.isEmpty()) {
      ctx.markUnavailable("warehouse", "7 库存总览里没有 SKU " + sku + " 的余额行");
      return;
    }

    for (var b : mine) {
      var onHand = num(b.onHand());
      var reserved = num(b.reserved());
      var available = num(b.available());

      // ---- 规则 1：可用库存归零 ----
      if (available.signum() <= 0) {
        ctx.findings.add(
            new Finding(
                "INVENTORY_STOCKOUT",
                "可用库存归零",
                "CRITICAL",
                "SKU " + sku + " 在仓库 " + b.warehouseCode() + " 的可用库存为 " + available.toPlainString() + " 件",
                "可用量 = 在手 " + onHand.toPlainString() + " − 预占 " + reserved.toPlainString()
                    + " = " + available.toPlainString() + " 件。可用量归零时该 SKU 已不可售。",
                available,
                "件",
                "",
                "核对 7 库存明细里的库位与质量状态：确认是真实断货，还是全部库存已被订单预占。",
                "GET /warehouse/inventory/overview",
                "7 库存",
                List.of(
                    new Evidence(
                        "warehouse",
                        "inventory_balance",
                        "available",
                        available,
                        "",
                        "件",
                        b.balanceId() == null ? "" : b.balanceId().toString(),
                        null,
                        "7 库存总览行的 available 是生成列（在手 − 预占），快照时点读到。"))));
      }

      // ---- 规则 2：在手超过超储阈值 ----
      if (b.thresholdConfigured() && b.maxOnHand() != null && onHand.compareTo(b.maxOnHand()) > 0) {
        var over = onHand.subtract(b.maxOnHand());
        ctx.findings.add(
            new Finding(
                "INVENTORY_OVERSTOCK",
                "在手超过超储阈值",
                "WARNING",
                "SKU " + sku + " 在仓库 " + b.warehouseCode() + " 的在手 " + onHand.toPlainString()
                    + " 件，超过超储阈值 " + b.maxOnHand().toPlainString() + " 件",
                "超储 " + over.toPlainString() + " 件；在手超过策略上限会压占仓储与资金。",
                over,
                "件",
                "",
                "按超储处理：暂停补货、安排调拨到有需求的仓，或走清货渠道。",
                "GET /warehouse/inventory/overview",
                "7 库存",
                List.of(
                    new Evidence(
                        "warehouse",
                        "inventory_policy",
                        "max_on_hand",
                        b.maxOnHand(),
                        "",
                        "件",
                        b.warehouseCode() == null ? "" : b.warehouseCode(),
                        null,
                        "该 SKU 在此仓的库存策略上限（超储阈值），快照时点读到。"),
                    new Evidence(
                        "warehouse",
                        "inventory_balance",
                        "on_hand",
                        onHand,
                        "",
                        "件",
                        b.balanceId() == null ? "" : b.balanceId().toString(),
                        null,
                        "同一行的在手量；与 max_on_hand 相减得到超储件数。"))));
      }

      // ---- 规则 3：库存几乎全被预占 ----
      if (onHand.signum() > 0
          && reserved.divide(onHand, 6, RoundingMode.HALF_UP).compareTo(RESERVED_RATIO_THRESHOLD) >= 0) {
        var ratio = scale6(reserved.divide(onHand, 6, RoundingMode.HALF_UP));
        ctx.findings.add(
            new Finding(
                "INVENTORY_RESERVED_BLOCKED",
                "库存几乎全被预占",
                "WARNING",
                "SKU " + sku + " 在仓库 " + b.warehouseCode() + " 的预占占在手 "
                    + percentText(ratio) + "（预占 " + reserved.toPlainString() + " / 在手 "
                    + onHand.toPlainString() + "）",
                "预占比例达到 " + percentText(ratio) + "，超过阈值 "
                    + percentText(RESERVED_RATIO_THRESHOLD)
                    + "。库存看着有货，但已被订单占住，可售量被吃掉。",
                reserved,
                "件",
                percentText(ratio),
                "核对在途订单与预占时效：已取消或未支付的订单释放预占后，可用库存会回来。",
                "GET /warehouse/inventory/overview",
                "9 订单 / 7 库存",
                List.of(
                    new Evidence(
                        "warehouse",
                        "inventory_balance",
                        "reserved",
                        reserved,
                        "",
                        "件",
                        b.balanceId() == null ? "" : b.balanceId().toString(),
                        null,
                        "该行的预占量（订单已占但未出库），快照时点读到。"),
                    new Evidence(
                        "warehouse",
                        "inventory_balance",
                        "on_hand",
                        onHand,
                        "",
                        "件",
                        b.balanceId() == null ? "" : b.balanceId().toString(),
                        null,
                        "同一行的在手量；预占 ÷ 在手 得到预占占比。"))));
      }
    }

    // ---- 规则 4：销量突增 ----
    var today = LocalDate.now(ZoneOffset.UTC);
    var recentFrom = today.minusDays(RECENT_WINDOW_DAYS - 1);
    var priorFrom = today.minusDays(RECENT_WINDOW_DAYS + PRIOR_WINDOW_DAYS - 1);
    var daily = sales.salesBySku(priorFrom, today, bearer);
    ctx.markRead("reporting");
    var recent = dailyAvg(daily, sku, recentFrom, today);
    var prior = dailyAvg(daily, sku, priorFrom, today.minusDays(RECENT_WINDOW_DAYS));
    if (prior.signum() > 0) {
      var ratio = scale6(recent.divide(prior, 6, RoundingMode.HALF_UP));
      if (ratio.compareTo(SALES_SURGE_RATIO) >= 0) {
        var rate = changeRate(recent, prior);
        ctx.findings.add(
            new Finding(
                "INVENTORY_SALES_SURGE",
                "销量突增",
                "WARNING",
                "SKU " + sku + " 近 7 天日均销量 " + recent.toPlainString() + " 件，是前 7 天（"
                    + prior.toPlainString() + " 件）的 " + ratio.toPlainString() + " 倍",
                "近 7 天日均 ÷ 前 7 天日均 = " + ratio.toPlainString() + " 倍，超过阈值 "
                    + SALES_SURGE_RATIO.toPlainString()
                    + " 倍。日均按整条日历算（没有记录的日子按 0 销量计）。",
                recent.subtract(prior),
                "件/天",
                percentText(rate),
                "按突增量核对补货节奏：现有库存能不能撑住这波销量，必要时提前下单。",
                "GET /reporting/sales-trend",
                "6 采购 / 13 分析",
                List.of(
                    new Evidence(
                        "reporting",
                        "reporting_sales_trend_fact",
                        "quantity",
                        recent,
                        "",
                        "件/天",
                        sku,
                        null,
                        "近 7 天日均销量：PRODUCT_SALES_QUANTITY 的 quantity 列按整条日历求和除以 7。"),
                    new Evidence(
                        "reporting",
                        "reporting_sales_trend_fact",
                        "quantity",
                        prior,
                        "",
                        "件/天",
                        sku,
                        null,
                        "前 7 天日均销量：同一指标、同一 SKU 的前一个等长窗口。"))));
      }
    }
  }

  /** 拉全部库存头寸（真分页）。 */
  private List<WarehouseInventoryPort.BalanceRow> fetchBalances(String bearer) {
    var out = new ArrayList<WarehouseInventoryPort.BalanceRow>();
    var page = 0;
    while (page < PlatformUpstreamClient.MAX_PAGES) {
      final int current = page;
      var batch = inventory.balances(current, PlatformUpstreamClient.PAGE_SIZE, bearer);
      var rows = batch.rows() == null ? List.<WarehouseInventoryPort.BalanceRow>of() : batch.rows();
      out.addAll(rows);
      if (rows.isEmpty()) break;
      if (rows.size() < PlatformUpstreamClient.PAGE_SIZE) break;
      page++;
    }
    return out;
  }

  // ================================================================ 归因引擎 · 利润异常

  /**
   * 利润异常归因。
   *
   * <p>事实源：trade 的 {@code purchase_analytics_fact}（fact_kind='COST'）+
   * reporting 的 {@code reporting_sales_trend_fact}（成交金额与销量）。
   */
  private void diagnoseProfit(AiDiagnosisEventRow event, RunContext ctx, String bearer) {
    var sku = event.subjectRef();
    var today = LocalDate.now(ZoneOffset.UTC);
    var recentFrom = today.minusDays(RECENT_WINDOW_DAYS - 1);
    var priorFrom = today.minusDays(RECENT_WINDOW_DAYS + PRIOR_WINDOW_DAYS - 1);
    var priorTo = today.minusDays(RECENT_WINDOW_DAYS);

    var costs = purchase.costFacts(priorFrom, today, sku, bearer);
    ctx.markRead("trade");
    var recentCosts =
        costs.stream().filter(c -> c.occurredDate() != null && !c.occurredDate().isBefore(recentFrom)).toList();
    var priorCosts =
        costs.stream()
            .filter(c -> c.occurredDate() != null && !c.occurredDate().isBefore(priorFrom)
                && c.occurredDate().isBefore(recentFrom))
            .toList();
    var recentCost = sumAmount(recentCosts);
    var priorCost = sumAmount(priorCosts);
    var recentUnit = weightedUnitPrice(recentCosts);
    var priorUnit = weightedUnitPrice(priorCosts);
    var currency = firstCurrency(recentCosts, priorCosts);

    // ---- 规则 1：采购单价上涨 ----
    if (priorUnit != null && recentUnit != null && priorUnit.signum() > 0) {
      var ratio = scale6(recentUnit.divide(priorUnit, 6, RoundingMode.HALF_UP));
      if (ratio.compareTo(COST_SPIKE_RATIO) >= 0) {
        var rate = changeRate(recentUnit, priorUnit);
        ctx.findings.add(
            new Finding(
                "PROFIT_COST_SPIKE",
                "采购单价上涨",
                "CRITICAL",
                "SKU " + sku + " 近 7 天采购加权均价 " + recentUnit.toPlainString()
                    + (currency == null ? "" : " " + currency) + "，比前 7 天的 " + priorUnit.toPlainString()
                    + " 上涨 " + percentText(rate),
                "加权均价 = Σamount ÷ Σquantity（近 7 天 ÷ 前 7 天 = " + ratio.toPlainString()
                    + " 倍，超过阈值 " + COST_SPIKE_RATIO.toPlainString() + " 倍）。成本直接吃掉毛利。",
                recentUnit.subtract(priorUnit),
                currency == null ? "金额/件" : currency + "/件",
                percentText(rate),
                "复核采购价与头程报价：确认是供应商调价还是结构变化；必要时换供应商或调整定价。",
                "GET /trade/purchase-analytics/facts",
                "6 采购",
                List.of(
                    new Evidence(
                        "trade",
                        "purchase_analytics_fact",
                        "unit_price",
                        recentUnit,
                        "",
                        currency == null ? "金额/件" : currency + "/件",
                        sku,
                        null,
                        "近 7 天采购加权均价：fact_kind='COST' 的 Σamount ÷ Σquantity。"),
                    new Evidence(
                        "trade",
                        "purchase_analytics_fact",
                        "unit_price",
                        priorUnit,
                        "",
                        currency == null ? "金额/件" : currency + "/件",
                        sku,
                        null,
                        "前 7 天采购加权均价：同一 SKU 的前一个等长窗口，作为涨幅基线。"))));
      }
    }

    // ---- 销售额侧（reporting）----
    var daily = sales.salesBySku(priorFrom, today, bearer);
    ctx.markRead("reporting");
    var recentRevenue = sumRevenue(daily, sku, recentFrom, today);
    var priorRevenue = sumRevenue(daily, sku, priorFrom, priorTo);
    var recentUnits = sumUnits(daily, sku, recentFrom, today);
    var priorUnits = sumUnits(daily, sku, priorFrom, priorTo);
    var salesCurrency = firstSalesCurrency(daily, sku);

    // ---- 规则 2：成交均价下跌 ----
    if (recentRevenue != null && priorRevenue != null && recentUnits.signum() > 0 && priorUnits.signum() > 0) {
      var recentPrice = scale6(recentRevenue.divide(recentUnits, 6, RoundingMode.HALF_UP));
      var priorPrice = scale6(priorRevenue.divide(priorUnits, 6, RoundingMode.HALF_UP));
      if (priorPrice.signum() > 0) {
        var ratio = scale6(recentPrice.divide(priorPrice, 6, RoundingMode.HALF_UP));
        if (ratio.compareTo(PRICE_DROP_RATIO) <= 0) {
          var rate = changeRate(recentPrice, priorPrice);
          ctx.findings.add(
              new Finding(
                  "PROFIT_PRICE_DROP",
                  "成交均价下跌",
                  "CRITICAL",
                  "SKU " + sku + " 近 7 天成交均价 " + recentPrice.toPlainString()
                      + (salesCurrency == null ? "" : " " + salesCurrency) + "，比前 7 天的 "
                      + priorPrice.toPlainString() + " 下跌 " + percentText(rate),
                  "均价 = 成交金额 ÷ 销量（近 7 天 ÷ 前 7 天 = " + ratio.toPlainString()
                      + " 倍，低于阈值 " + PRICE_DROP_RATIO.toPlainString() + " 倍）。同样的销量换来更少的钱。",
                  // 🔴 **impact 是幅度（不是带符号的差值）**：库里的 CHECK 是 impact >= 0，
                  //   方向由 impactText（带符号百分比，如 -40.00%）承担 ——
                  //   「跌了 2.00/件（-40.00%）」既不丢方向，也不违反幅度口径。
                  priorPrice.subtract(recentPrice),
                  salesCurrency == null ? "金额/件" : salesCurrency + "/件",
                  percentText(rate),
                  "核对是否有促销或竞品降价：若销量同步上来了，判断这波是不是「用价格换量」。",
                  "GET /reporting/sales-trend",
                  "5 定价 / 13 分析",
                  List.of(
                      new Evidence(
                          "reporting",
                          "reporting_sales_trend_fact",
                          "value",
                          recentRevenue,
                          "",
                          salesCurrency == null ? "金额" : salesCurrency,
                          sku,
                          null,
                          "近 7 天成交金额合计（PRODUCT_SALES_AMOUNT 的 value 列）。"),
                      new Evidence(
                          "reporting",
                          "reporting_sales_trend_fact",
                          "quantity",
                          recentUnits,
                          "",
                          "件",
                          sku,
                          null,
                          "近 7 天销量合计（PRODUCT_SALES_QUANTITY 的 quantity 列）；与金额相除得均价。"),
                      new Evidence(
                          "reporting",
                          "reporting_sales_trend_fact",
                          "value",
                          priorRevenue,
                          "",
                          salesCurrency == null ? "金额" : salesCurrency,
                          sku,
                          null,
                          "前 7 天成交金额合计：作为均价跌幅的基线。"))));
        }
      }
    }

    // ---- 规则 3：成本占销售额比例过高 ----
    if (recentCost != null && recentRevenue != null
        && recentRevenue.signum() > 0
        && sameCurrency(currency, salesCurrency)) {
      var ratio = scale6(recentCost.divide(recentRevenue, 6, RoundingMode.HALF_UP));
      if (ratio.compareTo(COST_RATIO_THRESHOLD) >= 0) {
        ctx.findings.add(
            new Finding(
                "PROFIT_COST_RATIO_HIGH",
                "成本占销售额比例过高",
                "WARNING",
                "SKU " + sku + " 近 7 天采购金额占销售额 " + percentText(ratio)
                    + "（成本 " + recentCost.toPlainString() + " / 销售额 " + recentRevenue.toPlainString() + "）",
                "成本占销售额比 = Σ采购金额 ÷ Σ成交金额 = " + ratio.toPlainString()
                    + "，超过阈值 " + COST_RATIO_THRESHOLD.toPlainString() + "。",
                recentCost,
                salesCurrency == null ? "金额" : salesCurrency,
                percentText(ratio),
                "复核该 SKU 的成本结构；优先处理成本占比最高的 SKU，而不是整体调价。",
                "GET /trade/purchase-analytics/facts",
                "6 采购 / 5 定价",
                List.of(
                    new Evidence(
                        "trade",
                        "purchase_analytics_fact",
                        "amount",
                        recentCost,
                        "",
                        salesCurrency == null ? "金额" : salesCurrency,
                        sku,
                        null,
                        "近 7 天采购金额合计（fact_kind='COST' 的 amount 列）。"),
                    new Evidence(
                        "reporting",
                        "reporting_sales_trend_fact",
                        "value",
                        recentRevenue,
                        "",
                        salesCurrency == null ? "金额" : salesCurrency,
                        sku,
                        null,
                        "近 7 天成交金额合计（PRODUCT_SALES_AMOUNT 的 value 列）；与成本相除得成本占比。"))));
      }
    } else if (recentRevenue != null && recentCost != null && recentRevenue.signum() > 0) {
      ctx.markUnavailable(
          "reporting", "销售额与采购金额不是同一币种 ⇒ 不做成本占比判定（不跨币种相加）");
    }
  }

  // ================================================================ 归因引擎 · 发货延迟

  /**
   * 发货延迟归因。
   *
   * <p>事实源：trade 的 {@code order_timeliness_fact}（经
   * {@code GET /trade/fulfillment/timeliness/facts} 读出）。
   */
  private void diagnoseShippingDelay(
      AiDiagnosisEventRow event, RunContext ctx, String bearer) {
    var orderNo = event.subjectRef();
    var facts = timeliness.factsAll(orderNo, bearer);
    ctx.markRead("trade");
    var fact =
        facts.stream()
            .filter(
                f ->
                    orderNo.equals(f.salesOrderNo())
                        || orderNo.equals(f.factNo())
                        || (f.id() != null && orderNo.equals(f.id())))
            .findFirst()
            .orElse(null);
    if (fact == null) {
      ctx.markUnavailable("trade", "9 履约的时效事实里查不到订单 " + orderNo);
      return;
    }

    // ---- 规则 1：发货 SLA 超时 ----
    if ("OVERDUE".equals(fact.shipSlaState()) && fact.shipOverdueSeconds() != null
        && fact.shipOverdueSeconds() > 0) {
      var overdue = BigDecimal.valueOf(fact.shipOverdueSeconds());
      ctx.findings.add(
          new Finding(
              "SHIPPING_SLA_OVERDUE",
              "发货 SLA 超时",
              "CRITICAL",
              "订单 " + orderNo + " 的发货 SLA 状态为 OVERDUE，已超时 "
                  + overdue.toPlainString() + " 秒",
              "超时秒数直接取自时效事实的 ship_overdue_seconds；"
                  + "SLA 截止时刻 " + (fact.shipSlaDeadlineAt() == null ? "（未记录）" : fact.shipSlaDeadlineAt())
                  + "。",
              overdue,
              "秒",
              "",
              "立刻联系发货仓催交；若整批都超时，调整该仓的发货 SLA 承诺并通知运营。",
              "GET /trade/fulfillment/timeliness/facts",
              "9 履约",
              List.of(
                  new Evidence(
                      "trade",
                      "order_timeliness_fact",
                      "ship_overdue_seconds",
                      overdue,
                      "",
                      "秒",
                      fact.factNo() == null ? orderNo : fact.factNo(),
                      null,
                      "该订单相对发货 SLA 截止时刻的超时秒数，快照时点读到。"),
                  new Evidence(
                      "trade",
                      "order_timeliness_fact",
                      "ship_sla_state",
                      null,
                      fact.shipSlaState(),
                      "",
                      fact.factNo() == null ? orderNo : fact.factNo(),
                      null,
                      "发货 SLA 状态（ON_TIME / OVERDUE / UNSET）；与超时秒数一起构成依据。"))));
    }

    // ---- 规则 2：订单到出库慢 ----
    if (fact.orderToPackSeconds() != null
        && fact.orderToPackSeconds() >= ORDER_TO_PACK_THRESHOLD_SECONDS) {
      var seconds = BigDecimal.valueOf(fact.orderToPackSeconds());
      ctx.findings.add(
          new Finding(
              "SHIPPING_ORDER_TO_PACK_SLOW",
              "订单到出库慢",
              "WARNING",
              "订单 " + orderNo + " 从下单到出库用了 " + seconds.toPlainString() + " 秒（≥ 24 小时）",
              "订单→出库段耗时取自时效事实的 order_to_pack_seconds；超过阈值 "
                  + ORDER_TO_PACK_THRESHOLD_SECONDS + " 秒。",
              seconds,
              "秒",
              "",
              "查拣货与库存分配环节：若该 SKU 缺货，优先从有货仓调拨。",
              "GET /trade/fulfillment/timeliness/facts",
              "7 库存 / 9 履约",
              List.of(
                  new Evidence(
                      "trade",
                      "order_timeliness_fact",
                      "order_to_pack_seconds",
                      seconds,
                      "",
                      "秒",
                      fact.factNo() == null ? orderNo : fact.factNo(),
                      null,
                      "下单时刻到出库时刻的秒数（该段未测出时为 null，不按 0 计）。"))));
    }

    // ---- 规则 3：出库到交接慢 ----
    if (fact.packToHandoverSeconds() != null
        && fact.packToHandoverSeconds() >= PACK_TO_HANDOVER_THRESHOLD_SECONDS) {
      var seconds = BigDecimal.valueOf(fact.packToHandoverSeconds());
      ctx.findings.add(
          new Finding(
              "SHIPPING_PACK_TO_HANDOVER_SLOW",
              "出库到交接慢",
              "WARNING",
              "订单 " + orderNo + " 从出库到交接用了 " + seconds.toPlainString() + " 秒（≥ 12 小时）",
              "出库→交接段耗时取自时效事实的 pack_to_handover_seconds；超过阈值 "
                  + PACK_TO_HANDOVER_THRESHOLD_SECONDS + " 秒。",
              seconds,
              "秒",
              "",
              "查承运商上门时效与仓库交接队列；连续发生时评估更换承运商。",
              "GET /trade/fulfillment/timeliness/facts",
              "9 履约",
              List.of(
                  new Evidence(
                      "trade",
                      "order_timeliness_fact",
                      "pack_to_handover_seconds",
                      seconds,
                      "",
                      "秒",
                      fact.factNo() == null ? orderNo : fact.factNo(),
                      null,
                      "出库时刻到交接时刻的秒数（该段未测出时为 null，不按 0 计）。"))));
    }

    // ---- 规则 4：承诺到货未达成 ----
    if ("LATE".equals(fact.promiseVsDeliveryState())) {
      var seconds =
          fact.promiseVsDeliverySeconds() == null
              ? null
              : BigDecimal.valueOf(fact.promiseVsDeliverySeconds());
      ctx.findings.add(
          new Finding(
              "SHIPPING_PROMISE_LATE",
              "承诺到货未达成",
              "CRITICAL",
              "订单 " + orderNo + " 的承诺到货状态为 LATE"
                  + (seconds == null ? "" : "（迟 " + seconds.toPlainString() + " 秒）"),
              "承诺到货时刻是**人工登记**的凭据（10.3 轨迹未交付），"
                  + "实际到货时刻 " + (fact.actualDeliveredAt() == null ? "（未登记）" : fact.actualDeliveredAt())
                  + "，承诺时刻 " + (fact.promisedDeliveryAt() == null ? "（未登记）" : fact.promisedDeliveryAt())
                  + "。",
              seconds,
              "秒",
              "",
              "主动联系买家给出补救方案；若同一仓反复发生，复核承诺时效配置。",
              "GET /trade/fulfillment/timeliness/facts",
              "9 履约 / 10 售后",
              List.of(
                  new Evidence(
                      "trade",
                      "order_timeliness_fact",
                      "promise_vs_delivery_state",
                      null,
                      fact.promiseVsDeliveryState(),
                      "",
                      fact.factNo() == null ? orderNo : fact.factNo(),
                      null,
                      "承诺到货与实际到货的对比状态（MET / LATE / …）。"),
                  new Evidence(
                      "trade",
                      "order_timeliness_fact",
                      "promise_vs_delivery_seconds",
                      seconds,
                      "",
                      "秒",
                      fact.factNo() == null ? orderNo : fact.factNo(),
                      null,
                      "承诺到货与实际到货的秒数差（未登记实际到货时为 null）。"))));
    }
  }

  // ================================================================ 归因引擎 · 同步失败

  /**
   * 同步失败归因。
   *
   * <p>事实源：**平台自己的三张失败源表**（V413 / V414 / V422），<b>只读</b>。
   */
  private void diagnoseSyncFailure(AiDiagnosisEventRow event, RunContext ctx) {
    ctx.markRead("platform");
    var subjectType = event.subjectType();
    var subjectRef = event.subjectRef();

    if ("SUBSCRIPTION".equals(subjectType)) {
      var uuid = parseUuid(subjectRef);
      if (uuid == null) {
        ctx.markUnavailable("platform", "订阅 id 不是合法 UUID：" + subjectRef);
        return;
      }
      var rows =
          sql.fetch(
              "select id,event_id,event_type,status,status_code,attempt_count,last_error,"
                  + " created_at from platform_delivery_record where subscription_id = ?::uuid"
                  + " order by created_at desc limit 20",
              uuid);
      if (rows.isEmpty()) {
        ctx.markUnavailable("platform", "platform_delivery_record 里没有该订阅的投递记录");
        return;
      }
      for (var r : rows) {
        var status = r.get("status", String.class);
        var attemptCount = r.get("attempt_count", Integer.class);
        var lastError = r.get("last_error", String.class);
        var createdAt = r.get("created_at", OffsetDateTime.class);
        if ("DEAD_LETTER".equals(status)) {
          ctx.findings.add(
              new Finding(
                  "SYNC_DELIVERY_DEAD_LETTER",
                  "投递进了死信",
                  "CRITICAL",
                  "订阅 " + subjectRef + " 的一次投递进了死信（已尝试 " + attemptCount + " 次）",
                  "最近错误：" + (lastError.isBlank() ? "（上游未记录）" : lastError),
                  BigDecimal.valueOf(attemptCount),
                  "次",
                  "",
                  "核对目标地址与签名密钥；修复后从死信箱重放该事件。",
                  "GET /platform/delivery-records",
                  "18.1.2 事件订阅",
                  List.of(
                      new Evidence(
                          "platform",
                          "platform_delivery_record",
                          "status",
                          null,
                          status,
                          "",
                          r.get("id", UUID.class).toString(),
                          createdAt,
                          "该投递记录的终态：重试次数用尽后转入死信箱。"),
                      new Evidence(
                          "platform",
                          "platform_delivery_record",
                          "attempt_count",
                          BigDecimal.valueOf(attemptCount),
                          "",
                          "次",
                          r.get("id", UUID.class).toString(),
                          createdAt,
                          "已尝试次数（含首次）；与 status 一起说明「为什么进死信」。"),
                      new Evidence(
                          "platform",
                          "platform_delivery_record",
                          "last_error",
                          null,
                          lastError,
                          "",
                          r.get("id", UUID.class).toString(),
                          createdAt,
                          "最近一次失败的原因原文（上游未记录时为空串）。"))));
        } else if ("FAILED".equals(status)) {
          ctx.findings.add(
              new Finding(
                  "SYNC_DELIVERY_FAILED",
                  "投递永久失败",
                  "WARNING",
                  "订阅 " + subjectRef + " 的一次投递永久失败（状态码 "
                      + r.get("status_code", Integer.class) + "）",
                  "最近错误：" + (lastError.isBlank() ? "（上游未记录）" : lastError),
                  BigDecimal.valueOf(r.get("status_code", Integer.class)),
                  "HTTP 状态码",
                  "",
                  "按 last_error 定位（地址非法 / 订阅停用 / 无生效签名密钥）并修复后重放。",
                  "GET /platform/delivery-records",
                  "18.1.2 事件订阅",
                  List.of(
                      new Evidence(
                          "platform",
                          "platform_delivery_record",
                          "status",
                          null,
                          status,
                          "",
                          r.get("id", UUID.class).toString(),
                          createdAt,
                          "该投递记录的状态：FAILED 表示**不会自动重试**（与死信不同）。"),
                      new Evidence(
                          "platform",
                          "platform_delivery_record",
                          "status_code",
                          BigDecimal.valueOf(r.get("status_code", Integer.class)),
                          "",
                          "HTTP 状态码",
                          r.get("id", UUID.class).toString(),
                          createdAt,
                          "最近一次尝试拿到的响应码（0 = 根本没拿到响应）。"))));
        }
      }
      return;
    }

    if ("ENDPOINT".equals(subjectType) || "APP".equals(subjectType)) {
      var rows =
          "ENDPOINT".equals(subjectType)
              ? sql.fetch(
                  "select id,endpoint_code,http_method,status_code,outcome,error_code,rate_limited,"
                      + " called_at from platform_api_call_log where endpoint_code = ?"
                      + " order by called_at desc limit 20",
                  subjectRef)
              : sql.fetch(
                  "select id,endpoint_code,http_method,status_code,outcome,error_code,rate_limited,"
                      + " called_at from platform_api_call_log where app_id = ?::uuid"
                      + " order by called_at desc limit 20",
                  subjectRef);
      if (rows.isEmpty()) {
        ctx.markUnavailable("platform", "platform_api_call_log 里没有该对象的调用记录");
        return;
      }
      for (var r : rows) {
        var outcome = r.get("outcome", String.class);
        var calledAt = r.get("called_at", OffsetDateTime.class);
        var recordId = r.get("id", UUID.class).toString();
        if (Boolean.TRUE.equals(r.get("rate_limited", Boolean.class))) {
          ctx.findings.add(
              new Finding(
                  "SYNC_API_RATE_LIMITED",
                  "接口调用被限流",
                  "WARNING",
                  "接口 " + r.get("endpoint_code", String.class) + " 的一次调用被限流（状态码 "
                      + r.get("status_code", Integer.class) + "）",
                  "限流命中与「一次错误」是两件事：这里记的是**被配额挡下来**的那次调用。",
                  BigDecimal.valueOf(r.get("status_code", Integer.class)),
                  "HTTP 状态码",
                  "",
                  "按 18.1.1.1 调整该应用的配额窗口；必要时降低调用并发或错峰。",
                  "GET /platform/call-monitor",
                  "18.1.1 应用与配额",
                  List.of(
                      new Evidence(
                          "platform",
                          "platform_api_call_log",
                          "rate_limited",
                          BigDecimal.ONE,
                          "true",
                          "次",
                          recordId,
                          calledAt,
                          "限流命中标记（与状态码分开记；命中时状态码必为 429）。"),
                      new Evidence(
                          "platform",
                          "platform_api_call_log",
                          "status_code",
                          BigDecimal.valueOf(r.get("status_code", Integer.class)),
                          "",
                          "HTTP 状态码",
                          recordId,
                          calledAt,
                          "这次调用的响应码。"))));
        }
        if ("AUTH_FAILED".equals(outcome)) {
          var errorCode = r.get("error_code", String.class);
          ctx.findings.add(
              new Finding(
                  "SYNC_API_AUTH_FAILED",
                  "接口调用鉴权失败",
                  "CRITICAL",
                  "接口 " + r.get("endpoint_code", String.class) + " 的一次调用鉴权失败（错误码 "
                      + (errorCode.isBlank() ? "（上游未记录）" : errorCode) + "）",
                  "错误码细到 SECRET_MISMATCH / IP_NOT_ALLOWED / SCOPE_DENIED / QUOTA_EXCEEDED，"
                      + "按它定位更准。",
                  BigDecimal.valueOf(r.get("status_code", Integer.class)),
                  "HTTP 状态码",
                  "",
                  "核对凭证是否过期 / SECRET 是否匹配 / IP 白名单是否放行该调用方。",
                  "GET /platform/call-monitor",
                  "18.1.1 应用与配额",
                  List.of(
                      new Evidence(
                          "platform",
                          "platform_api_call_log",
                          "outcome",
                          null,
                          outcome,
                          "",
                          recordId,
                          calledAt,
                          "调用结论码：AUTH_FAILED 表示凭证 / 权限没过。"),
                      new Evidence(
                          "platform",
                          "platform_api_call_log",
                          "error_code",
                          null,
                          errorCode,
                          "",
                          recordId,
                          calledAt,
                          "细分失败原因（上游未记录时为空串）。"))));
        }
      }
      return;
    }

    if ("RULE".equals(subjectType)) {
      var uuid = parseUuid(subjectRef);
      if (uuid == null) {
        ctx.markUnavailable("platform", "规则 id 不是合法 UUID：" + subjectRef);
        return;
      }
      var rows =
          sql.fetch(
              "select id,rule_id,rule_name,outcome,failure_class,error_code,error_message,"
                  + " not_attempted_reason,duration_ms,started_at from platform_automation_rule_run"
                  + " where rule_id = ?::uuid order by started_at desc limit 20",
              uuid);
      if (rows.isEmpty()) {
        ctx.markUnavailable("platform", "platform_automation_rule_run 里没有该规则的执行记录");
        return;
      }
      for (var r : rows) {
        var outcome = r.get("outcome", String.class);
        var startedAt = r.get("started_at", OffsetDateTime.class);
        var recordId = r.get("id", UUID.class).toString();
        if ("FAILED".equals(outcome)) {
          var failureClass = r.get("failure_class", String.class);
          var errorMessage = r.get("error_message", String.class);
          ctx.findings.add(
              new Finding(
                  "SYNC_RULE_RUN_FAILED",
                  "规则执行失败",
                  "WARNING",
                  "规则 " + r.get("rule_name", String.class) + " 的一次执行失败（失败分类 "
                      + (failureClass.isBlank() ? "（无）" : failureClass) + "）",
                  "错误信息：" + (errorMessage.isBlank() ? "（上游未记录）" : errorMessage),
                  BigDecimal.valueOf(r.get("duration_ms", Long.class)),
                  "毫秒",
                  "",
                  "按 failure_class 定位（ACTION_FAILED / ENGINE_ERROR / …）；动作本身的失败归动作所属域。",
                  "GET /platform/automation/runs",
                  "18.3 自动化",
                  List.of(
                      new Evidence(
                          "platform",
                          "platform_automation_rule_run",
                          "outcome",
                          null,
                          outcome,
                          "",
                          recordId,
                          startedAt,
                          "本次执行的结论码。"),
                      new Evidence(
                          "platform",
                          "platform_automation_rule_run",
                          "failure_class",
                          null,
                          failureClass,
                          "",
                          recordId,
                          startedAt,
                          "失败分类：CONDITION_NOT_MATCHED / ACTION_FAILED / TIMEOUT / RULE_DISABLED / "
                              + "ENGINE_ERROR / EXECUTOR_UNAVAILABLE。"))));
        } else if ("TIMEOUT".equals(outcome)) {
          ctx.findings.add(
              new Finding(
                  "SYNC_RULE_RUN_TIMEOUT",
                  "规则执行超时",
                  "WARNING",
                  "规则 " + r.get("rule_name", String.class) + " 的一次执行超时（耗时 "
                      + r.get("duration_ms", Long.class) + " 毫秒）",
                  "耗时是**整数毫秒**（不是浮点）；超时说明这次触发的体量超出了规则能承载的规模。",
                  BigDecimal.valueOf(r.get("duration_ms", Long.class)),
                  "毫秒",
                  "",
                  "看该规则的触发体量：必要时拆分动作、缩小条件范围或调高超时。",
                  "GET /platform/automation/runs",
                  "18.3 自动化",
                  List.of(
                      new Evidence(
                          "platform",
                          "platform_automation_rule_run",
                          "outcome",
                          null,
                          outcome,
                          "",
                          recordId,
                          startedAt,
                          "本次执行的结论码。"),
                      new Evidence(
                          "platform",
                          "platform_automation_rule_run",
                          "duration_ms",
                          BigDecimal.valueOf(r.get("duration_ms", Long.class)),
                          "",
                          "毫秒",
                          recordId,
                          startedAt,
                          "本次执行的总耗时（整数毫秒）。"))));
        } else if ("NOT_ATTEMPTED".equals(outcome)) {
          var reason = r.get("not_attempted_reason", String.class);
          ctx.findings.add(
              new Finding(
                  "SYNC_RULE_RUN_NOT_ATTEMPTED",
                  "规则未执行（执行器未接通）",
                  "INFO",
                  "规则 " + r.get("rule_name", String.class) + " 的一次执行未执行（NOT_ATTEMPTED）",
                  "未接通原因：" + (reason.isBlank() ? "（上游未记录）" : reason),
                  null,
                  "",
                  "",
                  "这是**能力缺口**而不是故障：等 18.3.2 的执行器接通后重跑即可。",
                  "GET /platform/automation/runs",
                  "18.3 自动化",
                  List.of(
                      new Evidence(
                          "platform",
                          "platform_automation_rule_run",
                          "outcome",
                          null,
                          outcome,
                          "",
                          recordId,
                          startedAt,
                          "本次执行的结论码：NOT_ATTEMPTED 表示执行器未接通，不是失败。"),
                      new Evidence(
                          "platform",
                          "platform_automation_rule_run",
                          "not_attempted_reason",
                          null,
                          reason,
                          "",
                          recordId,
                          startedAt,
                          "未接通原因原文（上游未记录时为空串）。"))));
        }
      }
      return;
    }

    ctx.markUnavailable("platform", "异常对象类型 " + subjectType + " 没有对应的失败源表");
  }

  // ================================================================ 归因引擎 · 广告亏损

  /**
   * 广告亏损归因。
   *
   * <p>事实源：channel 的 {@code ad_campaign_daily_metric}（经 14.3.1.1 活动报表读出）。
   */
  private void diagnoseAdLoss(AiDiagnosisEventRow event, RunContext ctx, String bearer) {
    var campaignId = event.subjectRef();
    var today = LocalDate.now(ZoneOffset.UTC);
    var from = today.minusDays(RECENT_WINDOW_DAYS + PRIOR_WINDOW_DAYS - 1);
    var page = ads.campaignRowsAll(from, today, bearer);
    ctx.markRead("channel");
    var row =
        page.items().stream()
            .filter(r -> campaignId.equals(r.dimensionKey()) || campaignId.equals(r.externalCampaignId()))
            .findFirst()
            .orElse(null);
    if (row == null) {
      ctx.markUnavailable(
          "channel",
          "广告日报表里没有活动 " + campaignId + " 的行"
              + (page.emptyReason() == null
                  ? ""
                  : "（上游空态原因：" + page.emptyReason() + "；" + page.emptyNote() + "）"));
      return;
    }

    var spend = row.spend();
    var sales = row.sales();
    var currency = row.currency();
    var window =
        "统计窗口 " + row.metricFrom() + " ~ " + row.metricTo()
            + "（上游按活动汇总，分组键为活动 id）";

    // ---- 规则 1：ACOS 超标 ----
    if (row.acos() != null && row.acos().compareTo(ACOS_THRESHOLD) >= 0) {
      ctx.findings.add(
          new Finding(
              "AD_ACOS_ABOVE_TARGET",
              "ACOS 超过阈值",
              "CRITICAL",
              "活动 " + row.dimensionLabel() + " 的 ACOS 为 " + row.acos().toPlainString()
                  + "，超过阈值 " + ACOS_THRESHOLD.toPlainString(),
              window + "；ACOS = Σ花费 ÷ Σ销售额（先汇总再相除，口径来自上游）。",
              row.acos(),
              "比例",
              percentText(row.acos()),
              "降竞价或收窄投放词；先核对商品毛利，再决定是否继续投放。",
              "GET /channel/ad-reports/rows",
              "14 广告",
              List.of(
                  new Evidence(
                      "channel",
                      "ad_campaign_daily_metric",
                      "acos",
                      row.acos(),
                      "",
                      "比例",
                      row.dimensionKey(),
                      null,
                      "该活动窗口内的 ACOS（生成列：spend ÷ sales），快照时点读到。"),
                  new Evidence(
                      "channel",
                      "ad_campaign_daily_metric",
                      "spend",
                      spend,
                      "",
                      currency == null ? "金额" : currency,
                      row.dimensionKey(),
                      null,
                      "该活动窗口内的花费合计；ACOS 的分子。"),
                  new Evidence(
                      "channel",
                      "ad_campaign_daily_metric",
                      "sales",
                      sales,
                      "",
                      currency == null ? "金额" : currency,
                      row.dimensionKey(),
                      null,
                      "该活动窗口内的销售额合计；ACOS 的分母。"))));
    }

    // ---- 规则 2：ROAS 低于盈亏平衡 ----
    if (row.roas() != null && row.roas().compareTo(ROAS_BREAKEVEN) < 0) {
      ctx.findings.add(
          new Finding(
              "AD_ROAS_BELOW_BREAKEVEN",
              "ROAS 低于盈亏平衡",
              "CRITICAL",
              "活动 " + row.dimensionLabel() + " 的 ROAS 为 " + row.roas().toPlainString()
                  + "，低于盈亏平衡 " + ROAS_BREAKEVEN.toPlainString(),
              window + "；ROAS = Σ销售额 ÷ Σ花费。小于 1 意味着花出去的钱没换回等额销售额。",
              row.roas(),
              "倍",
              "",
              "核对销售额统计口径是否包含该平台的订单；口径没问题则降价或暂停投放。",
              "GET /channel/ad-reports/rows",
              "14 广告",
              List.of(
                  new Evidence(
                      "channel",
                      "ad_campaign_daily_metric",
                      "roas",
                      row.roas(),
                      "",
                      "倍",
                      row.dimensionKey(),
                      null,
                      "该活动窗口内的 ROAS（生成列：sales ÷ spend）；分母为 0 时上游给 null。"),
                  new Evidence(
                      "channel",
                      "ad_campaign_daily_metric",
                      "spend",
                      spend,
                      "",
                      currency == null ? "金额" : currency,
                      row.dimensionKey(),
                      null,
                      "该活动窗口内的花费合计；ROAS 的分母。"),
                  new Evidence(
                      "channel",
                      "ad_campaign_daily_metric",
                      "sales",
                      sales,
                      "",
                      currency == null ? "金额" : currency,
                      row.dimensionKey(),
                      null,
                      "该活动窗口内的销售额合计；ROAS 的分子。"))));
    }

    // ---- 规则 3：有花费没有销售额 ----
    if (sales != null && sales.signum() <= 0 && spend != null && spend.signum() > 0) {
      ctx.findings.add(
          new Finding(
              "AD_SPEND_WITHOUT_SALES",
              "有花费没有销售额",
              "CRITICAL",
              "活动 " + row.dimensionLabel() + " 在窗口内花了 " + spend.toPlainString()
                  + (currency == null ? "" : " " + currency) + "，销售额为 0",
              window + "；ACOS / ROAS 此时都不可比（分母为 0），只能直接看花费与销售额这一对事实。",
              spend,
              currency == null ? "金额" : currency,
              "",
              "核对投放是否跑错页面 / 商品是否已断货；断货时先补货再投。",
              "GET /channel/ad-reports/rows",
              "14 广告 / 7 库存",
              List.of(
                  new Evidence(
                      "channel",
                      "ad_campaign_daily_metric",
                      "spend",
                      spend,
                      "",
                      currency == null ? "金额" : currency,
                      row.dimensionKey(),
                      null,
                      "该活动窗口内的花费合计（> 0）。"),
                  new Evidence(
                      "channel",
                      "ad_campaign_daily_metric",
                      "sales",
                      sales,
                      "",
                      currency == null ? "金额" : currency,
                      row.dimensionKey(),
                      null,
                      "该活动窗口内的销售额合计（= 0）；与花费一起构成「白花钱」的事实。"))));
    }

    // ---- 规则 4：点击率过低 ----
    if (row.ctr() != null && row.ctr().compareTo(CTR_THRESHOLD) < 0) {
      ctx.findings.add(
          new Finding(
              "AD_LOW_CTR",
              "点击率过低",
              "WARNING",
              "活动 " + row.dimensionLabel() + " 的 CTR 为 " + row.ctr().toPlainString()
                  + "，低于阈值 " + CTR_THRESHOLD.toPlainString(),
              window + "；CTR = Σ点击 ÷ Σ曝光。曝光为 0 时上游给 null（不可比），此时不触发本条。",
              row.ctr(),
              "比例",
              "",
              "换素材或换定向；曝光量太少时 CTR 本身不可比，先把曝光量做起来。",
              "GET /channel/ad-reports/rows",
              "14 广告",
              List.of(
                  new Evidence(
                      "channel",
                      "ad_campaign_daily_metric",
                      "ctr",
                      row.ctr(),
                      "",
                      "比例",
                      row.dimensionKey(),
                      null,
                      "该活动窗口内的 CTR（生成列：clicks ÷ impressions）。"),
                  new Evidence(
                      "channel",
                      "ad_campaign_daily_metric",
                      "clicks",
                      row.clicks(),
                      "",
                      "次",
                      row.dimensionKey(),
                      null,
                      "该活动窗口内的点击合计。"),
                  new Evidence(
                      "channel",
                      "ad_campaign_daily_metric",
                      "impressions",
                      row.impressions(),
                      "",
                      "次",
                      row.dimensionKey(),
                      null,
                      "该活动窗口内的曝光合计；与点击一起说明 CTR 的分母有多大。"))));
    }
  }

  // ================================================================ 小工具

  private static UUID parseUuid(String value) {
    if (value == null || value.isBlank()) return null;
    try {
      return UUID.fromString(value.trim());
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  /** 某 SKU 在一段日历内的**日均销量**（没有记录的日子按 0 销量计）。 */
  private static BigDecimal dailyAvg(
      List<ForecastSalesPort.DailySales> daily, String sku, LocalDate from, LocalDate to) {
    var sum =
        daily.stream()
            .filter(d -> sku.equals(d.skuCode()))
            .filter(d -> d.businessDate() != null && !d.businessDate().isBefore(from)
                && !d.businessDate().isAfter(to))
            .map(d -> num(d.units()))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    var days = java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1;
    if (days <= 0) return BigDecimal.ZERO;
    return scale6(sum.divide(BigDecimal.valueOf(days), 6, RoundingMode.HALF_UP));
  }

  /** 某 SKU 在一段日历内的成交金额合计（**多币种时为 null**：不跨币种相加）。 */
  private static BigDecimal sumRevenue(
      List<ForecastSalesPort.DailySales> daily, String sku, LocalDate from, LocalDate to) {
    var rows =
        daily.stream()
            .filter(d -> sku.equals(d.skuCode()))
            .filter(d -> d.businessDate() != null && !d.businessDate().isBefore(from)
                && !d.businessDate().isAfter(to))
            .toList();
    if (rows.isEmpty()) return null;
    var currencies = rows.stream().map(ForecastSalesPort.DailySales::currency).distinct().toList();
    if (currencies.size() != 1) return null;
    var sum =
        rows.stream()
            .map(d -> num(d.revenue()))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    return scale6(sum);
  }

  /** 某 SKU 在一段日历内的销量合计。 */
  private static BigDecimal sumUnits(
      List<ForecastSalesPort.DailySales> daily, String sku, LocalDate from, LocalDate to) {
    return scale6(
        daily.stream()
            .filter(d -> sku.equals(d.skuCode()))
            .filter(d -> d.businessDate() != null && !d.businessDate().isBefore(from)
                && !d.businessDate().isAfter(to))
            .map(d -> num(d.units()))
            .reduce(BigDecimal.ZERO, BigDecimal::add));
  }

  /** 一段销量里出现的币种（只有一个时才返回它；多币种 ⇒ null）。 */
  private static String firstSalesCurrency(List<ForecastSalesPort.DailySales> daily, String sku) {
    var currencies =
        daily.stream()
            .filter(d -> sku.equals(d.skuCode()))
            .map(ForecastSalesPort.DailySales::currency)
            .filter(c -> c != null && !c.isBlank())
            .distinct()
            .toList();
    return currencies.size() == 1 ? currencies.get(0) : null;
  }

  /** 一批采购事实的金额合计（**多币种时为 null**：不跨币种相加）。 */
  private static BigDecimal sumAmount(List<DiagnosisPurchasePort.CostFact> facts) {
    if (facts.isEmpty()) return null;
    var currencies =
        facts.stream()
            .map(DiagnosisPurchasePort.CostFact::currency)
            .filter(c -> c != null && !c.isBlank())
            .distinct()
            .toList();
    if (currencies.size() != 1) return null;
    var sum =
        facts.stream()
            .map(f -> num(f.amount()))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    return scale6(sum);
  }

  /**
   * 一批采购事实的**加权均价**（Σamount ÷ Σquantity）。
   *
   * <p>🔴 没有 quantity 时**不给价**（那会是一个假单价）⇒ 返回 null。
   */
  private static BigDecimal weightedUnitPrice(List<DiagnosisPurchasePort.CostFact> facts) {
    if (facts.isEmpty()) return null;
    var amount =
        facts.stream()
            .map(f -> num(f.amount()))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    var quantity =
        facts.stream()
            .map(f -> num(f.quantity()))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    if (quantity.signum() <= 0) return null;
    return scale6(amount.divide(quantity, 6, RoundingMode.HALF_UP));
  }

  /** 一批采购事实里的币种（只有一个时才返回它）。 */
  private static String firstCurrency(
      List<DiagnosisPurchasePort.CostFact> recent, List<DiagnosisPurchasePort.CostFact> prior) {
    var currencies = new LinkedHashSet<String>();
    for (var list : List.of(recent, prior)) {
      for (var f : list) {
        if (f.currency() != null && !f.currency().isBlank()) currencies.add(f.currency());
      }
    }
    return currencies.size() == 1 ? currencies.iterator().next() : null;
  }

  /** 两个币种是否**同一个**（null 视为「拿不到」⇒ 判 false，不跨币种相加）。 */
  private static boolean sameCurrency(String a, String b) {
    return a != null && !a.isBlank() && b != null && !b.isBlank() && a.equals(b);
  }
}
