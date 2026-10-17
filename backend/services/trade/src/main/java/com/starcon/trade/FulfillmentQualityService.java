package com.starcon.trade;

import static com.starcon.trade.FulfillmentQualityModels.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 9.5.2.1 履约质量。
 *
 * <p><b>八条实现纪律</b>（照抄同服务 9.5.1.1 已交付的写法，不另起风格）：
 *
 * <ol>
 *   <li><b>本模块是读模型</b>。不推进任何状态机、不回传平台、不改一张既有表。
 *       六个指标全部是每次查询现算的聚合，**不落表** —— 落了表就会与事实漂移，
 *       而指标看板的价值恰恰在于它随时能对着事实重算一遍。</li>
 *   <li><b>口径只有一处定义</b>：分子/分母写在 {@link QualityMetric} 枚举里，
 *       能力声明、指标卡、维度对比、下钻清单全部引用同一段话。
 *       口径含糊的指标看板等于没有指标。</li>
 *   <li><b>分母为 0 ⇒ null</b>。由 {@link #rateSql} 的
 *       {@code case when den = 0 then null else round(...) end} 在 SQL 里表达，
 *       服务层<b>不</b>把 null 兜成 0 或 1。分母为 0 的含义是「没有可判定样本」，
 *       报成 0 会被读成「及时率 0%」—— 那是结论，不是缺口。</li>
 *   <li><b>比率一律在 SQL 里算</b>。Java 侧只把 SQL 结果摆成卡片，
 *       不做任何累加或除法。</li>
 *   <li><b>每条查询只绑定两个参数</b>（窗口起止）。窗口在每条 SQL 里以
 *       {@code with win as (select ?::timestamptz as f, ?::timestamptz as t)} 出现一次，
 *       谓词引用 {@code (select f from win)} —— 这样指标口径里内嵌的窗口条件
 *       不需要各自带绑定值，参数顺序也就不会随口径变化而错位。</li>
 *   <li><b>同服务只读 join</b>：{@code order_timeliness_fact}（9.5.1.1）、
 *       {@code order_fulfillment_line_progress} 与 {@code order_cancellation_request}（V119）、
 *       {@code shipment_record}（9.4.1.2）、{@code sales_order}（V99）、
 *       {@code fulfillment_request*}（V152）全在 trade 库里，纯 SELECT，
 *       无 FK、无触发器、无写入、不 ALTER 对方表。</li>
 *   <li><b>租户隔离只靠 RLS</b>，WHERE 里<b>不写</b> {@code tenant_id} 谓词 ——
 *       与 9.5.1.1 / 9.4.1.2 同一写法。本模块是统计模块，聚合查询同样受
 *       FORCE RLS 保护。</li>
 *   <li><b>时间绑定</b>：{@code ?::timestamptz}，全程 {@code OffsetDateTime}，
 *       不用 {@code Timestamp.valueOf(LocalDateTime)}（那会静默偏 −28800s）。
 *       筛选值一律走 {@code ?} 绑定，不拼字面量。</li>
 * </ol>
 *
 * <p><b>跨服务缺口照实报出</b>：卷 11 售后在别的服务的独立库里，
 * AGENTS.md 禁止跨服务 SQL / 视图 / FK ⇒ 纠纷率与退货率当前读不到真实售后数据，
 * 只能读租户可维护的信号表（V155 第五节，照抄 9.2.2.2 / V150 的解法）。
 * {@link #capabilities()} 显式报 {@code crossServiceReadAvailable=false} +
 * {@code upstreamDelivered=false}，<b>不让调用方从 404 或空数组里猜哪一环没通</b>。
 *
 * <p><b>没有硬编码常量冒充数据</b>：六个指标要么走真聚合，要么报 unavailable + 原因。
 */
@Service
public class FulfillmentQualityService {

  /** 单页最大行数（与 9.4.1.2 / 9.5.1.1 同一上限）。 */
  static final int MAX_PAGE_SIZE = 200;

  /** 默认窗口跨度（天）。不默认成「全部历史」：指标看板要先有窗口才有意义。 */
  static final int DEFAULT_WINDOW_DAYS = 30;

  private static final BigDecimal HUNDRED = new BigDecimal("100");

  /**
   * 业务时区。与 9.5.1.1、9.4.1.2 同一口径：窗口归一化与「今天」都以它为准，
   * 否则跨零点会圈错半天。
   */
  private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

  /**
   * 窗口回显的固定格式。
   *
   * <p>刻意不用 {@link OffsetDateTime#toString()}：它在秒为 0 时会把秒段省掉
   * （{@code 2026-03-01T00:00:00+08:00} → {@code 2026-03-01T00:00+08:00}），
   * 瞬时没变但文本每次都不一样，调用方没法拿回显值做等值断言。
   */
  private static final DateTimeFormatter WINDOW_FMT =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

  /** 没有订单归属时用的维度占位符。刻意不并进任何有值分组（V155 第三节）。 */
  private static final String UNATTACHED = "UNATTACHED";

  /** 未认领人时的人员维度占位符。混进某个真实人员会让那个人的指标凭空变好。 */
  private static final String UNASSIGNED = "UNASSIGNED";

  /**
   * 人员维度的只读 join。
   *
   * <p>链路：{@code fulfillment_request_work_order.assignee_ref}（仓库侧作业系统
   * 回填的认领人）→ {@code fulfillment_request.order_ref} → {@code sales_order.order_no}。
   *
   * <p>⚠ {@code order_ref} 是 9.3.3.1 明确声明**只存引用不 JOIN** 的逻辑引用列
   * （V152 第 147 行），本模块按租户内唯一的 {@code order_no} 文本对齐。
   * 这是一处<b>口径选择</b>而非事实唯一性：认不上的一律落 {@code UNASSIGNED} 桶。
   *
   * <p>这个 join 会让一张订单在多人参与时**同时**落到每个人下面 ——
   * 所有聚合都用 {@code count(distinct 计量单元)}，所以展开不会放大计数。
   * 硬把一票货归给一个人会凭空制造责任归属，而「涉及该人员的订单」是可核对的表述。
   */
  private static final String PERSON_JOIN =
      " left join fulfillment_request fr on fr.order_ref = qo.order_no"
          + " left join fulfillment_request_work_order w on w.request_id = fr.id";

  private final DSLContext sql;

  public FulfillmentQualityService(DSLContext sql) {
    this.sql = sql;
  }

  // ================================================================ 指标规格

  /**
   * 单个指标的聚合规格。
   *
   * <p>谓词里用 {@code (select f from win)} / {@code (select t from win)} 引用窗口，
   * 所以内嵌窗口条件不需要自带绑定值（纪律 5）—— 口径变复杂时不会把参数顺序带错。
   *
   * @param unit 计量单元：货票 / 订单行 / 订单，count(distinct) 它就是分子分母
   * @param denPredicate 分母谓词（把「没测过」的样本排除在分母外，如 UNSET 时限）
   * @param numPredicate 分子谓词（行级布尔，可引用窗口）
   * @param excPredicate 刻意不进分母的样本数谓词
   */
  private record MetricSpec(
      QualityMetric metric,
      String baseFrom,
      String orderKey,
      String unit,
      String denPredicate,
      String numPredicate,
      String excPredicate,
      String drillKind,
      String drillClass,
      String drillClock) {

    /** 分组查询用的 SELECT 片段。 */
    String aggregates() {
      return " count(distinct " + unit + ") filter (where " + numPredicate + ") as num,"
          + " count(distinct " + unit + ") filter (where " + denPredicate + ") as den,"
          + " count(distinct " + unit + ") filter (where " + excPredicate + ") as exc,"
          + " count(distinct " + orderKey + ") as orders,"
          + rateSql(
              "count(distinct " + unit + ") filter (where " + numPredicate + ")",
              "count(distinct " + unit + ") filter (where " + denPredicate + ")")
          + " as rate";
    }
  }

  /**
   * 六个指标的规格表 —— <b>口径在此与 {@link QualityMetric} 双向对齐</b>。
   *
   * <p>逐条对应 V155 第二节：
   * <ol>
   *   <li>发货及时率：复用 9.5.1.1 冻结的发货时限快照，UNSET 不进分母；</li>
   *   <li>完整发货率：按<b>订单行</b>，分母是有进度投影且已发货的行；</li>
   *   <li>错发漏发率：分母是有进度投影的订单，分子是「有登记」或「系统看得出少发」；</li>
   *   <li>取消率：分母是窗口内已提交订单，分子按订单<b>最终状态</b>判 CONFIRMED；</li>
   *   <li>(6) 纠纷率 / 退货率：分母是窗口内有交接事实的订单，分子是窗口内该类信号。</li>
   * </ol>
   */
  private MetricSpec spec(QualityMetric m) {
    return switch (m) {
      case SHIP_ON_TIME_RATE ->
          new MetricSpec(
              m,
              "order_timeliness_fact t",
              "t.sales_order_id",
              "t.id",
              "t.ship_sla_state in ('ON_TIME','OVERDUE')",
              "t.ship_sla_state = 'ON_TIME'",
              "t.ship_sla_state = 'UNSET'",
              "TIMELINESS_FACT",
              "t.ship_sla_state",
              "t.handed_over_at");
      case COMPLETE_SHIPMENT_RATE ->
          new MetricSpec(
              m,
              "order_fulfillment_line_progress p",
              "p.order_id",
              "p.order_line_id",
              "true",
              "p.shipped_qty >= p.ordered_qty",
              "false",
              "ORDER_LINE_PROGRESS",
              "case when p.shipped_qty >= p.ordered_qty then 'COMPLETE' else 'INCOMPLETE' end",
              "p.shipped_at");
      case WRONG_OR_MISSING_RATE ->
          new MetricSpec(
              m,
              "order_fulfillment_line_progress p",
              "p.order_id",
              "p.order_id",
              "true",
              "(exists (select 1 from fulfillment_quality_fulfillment_exception x"
                  + "   where x.order_id = p.order_id"
                  + "     and x.reported_at >= (select f from win) and x.reported_at < (select t from win))"
                  + " or exists (select 1 from order_fulfillment_line_progress q"
                  + "   where q.order_id = p.order_id and q.shipped_qty < q.ordered_qty))",
              "false",
              "ORDER_WITH_DEFECT",
              "case"
                  + " when exists (select 1 from fulfillment_quality_fulfillment_exception x"
                  + "   where x.order_id = p.order_id and x.exception_type = 'WRONG_ITEM'"
                  + "     and x.reported_at >= (select f from win) and x.reported_at < (select t from win))"
                  + "   then 'WRONG_ITEM'"
                  + " when exists (select 1 from fulfillment_quality_fulfillment_exception x"
                  + "   where x.order_id = p.order_id and x.exception_type = 'MISSING_ITEM'"
                  + "     and x.reported_at >= (select f from win) and x.reported_at < (select t from win))"
                  + "   then 'MISSING_ITEM_DECLARED'"
                  + " else 'MISSING_ITEM_DERIVED' end",
              "p.shipped_at");
      case CANCELLATION_RATE ->
          new MetricSpec(
              m,
              "sales_order t",
              "t.id",
              "t.id",
              "true",
              "exists (select 1 from order_cancellation_request c"
                  + " where c.order_id = t.id and c.status = 'CONFIRMED')",
              "false",
              "SALES_ORDER",
              "case when exists (select 1 from order_cancellation_request c"
                  + "   where c.order_id = t.id and c.status = 'CONFIRMED')"
                  + " then 'CANCELLED' else 'NOT_CANCELLED' end",
              "t.submitted_at");
      case DISPUTE_RATE ->
          new MetricSpec(
              m,
              "shipment_record s",
              "s.sales_order_id",
              "s.sales_order_id",
              "s.sales_order_id is not null",
              "(s.sales_order_id is not null and exists (select 1 from"
                  + " fulfillment_quality_after_sale_signal g where g.order_id = s.sales_order_id"
                  + " and g.signal_type = 'DISPUTE'"
                  + " and g.occurred_at >= (select f from win) and g.occurred_at < (select t from win)))",
              "false",
              "SHIPMENT_RECORD",
              "case when exists (select 1 from fulfillment_quality_after_sale_signal g"
                  + "   where g.order_id = s.sales_order_id and g.signal_type = 'DISPUTE'"
                  + "   and g.occurred_at >= (select f from win) and g.occurred_at < (select t from win))"
                  + " then 'DISPUTED' else 'CLEAN' end",
              "s.shipped_at");
      case RETURN_RATE ->
          new MetricSpec(
              m,
              "shipment_record s",
              "s.sales_order_id",
              "s.sales_order_id",
              "s.sales_order_id is not null",
              "(s.sales_order_id is not null and exists (select 1 from"
                  + " fulfillment_quality_after_sale_signal g where g.order_id = s.sales_order_id"
                  + " and g.signal_type = 'RETURN'"
                  + " and g.occurred_at >= (select f from win) and g.occurred_at < (select t from win)))",
              "false",
              "SHIPMENT_RECORD",
              "case when exists (select 1 from fulfillment_quality_after_sale_signal g"
                  + "   where g.order_id = s.sales_order_id and g.signal_type = 'RETURN'"
                  + "   and g.occurred_at >= (select f from win) and g.occurred_at < (select t from win))"
                  + " then 'RETURNED' else 'CLEAN' end",
              "s.shipped_at");
    };
  }

  /**
   * 比率表达式。<b>分母为 0 ⇒ NULL</b>（纪律 3）。
   *
   * <p>刻意不在 Java 里兜底：Java 兜底会把「没有样本」悄悄变成 0% 或 100%，
   * 而这两种都不是事实。
   */
  private static String rateSql(String numerator, String denominator) {
    return " case when (" + denominator + ") = 0 then null"
        + " else round((" + numerator + ") * 100.0 / (" + denominator + "), 6) end";
  }

  // ================================================================ 能力声明

  /**
   * 本模块的能力与上下游可用性。
   *
   * <p>本模块有<b>三处</b>「拿不到却必须说」：店铺维度到不了 shop_code、
   * 错发没有 SKU 级上游、售后数据在别的服务的独立库。
   * 三处一律 {@code available=false} 并点名 ownerModule，同时说清本模块的处理方式。
   */
  public QualityCapabilities capabilities() {
    return new QualityCapabilities(
        List.of(
            new QualityCapabilityDimension(
                "SHIPMENT_TIMELINESS_FACT",
                "发货及时率（按时 / 超时 / 未登记时限 三桶）",
                true,
                null,
                null,
                "同服务 9.5.1.1 的 order_timeliness_fact（trade 库内**只读 join**）。"
                    + "刻意复用它冻结的发货时限快照口径，不另起一套时限算法 —— 同一件事两个算法必然对不上"),
            new QualityCapabilityDimension(
                "FULFILLMENT_LINE_PROGRESS",
                "完整发货率 / 漏发推导（订单行级下单量与已发量）",
                true,
                null,
                null,
                "同服务 V119 的 order_fulfillment_line_progress（只读 join）。"
                    + "**没有进度投影的行不是「不完整」而是「没测过」**，独立报数不进分母"),
            new QualityCapabilityDimension(
                "CANCELLATION_REQUEST",
                "取消率（已确认取消申请）",
                true,
                null,
                null,
                "同服务 V119 的 order_cancellation_request。只认 status='CONFIRMED'；"
                    + "REQUESTED / PROCESSING / PENDING_VERIFICATION 都还可能被驳回，"
                    + "把申请数当取消数会让取消率虚高一截"),
            new QualityCapabilityDimension(
                "WRONG_ITEM_IDENTITY",
                "错发（发出了不是订单行那个 SKU 的货）",
                false,
                "发货记录与运单只有件数与总量，**没有 SKU 列**；履约进度按 order_line_id 建行。"
                    + "集成头里没有任何「实际发出 SKU vs 订单行 SKU」的数据源",
                "9.4 履约反馈 / 10 物流轨迹",
                "本模块**不判定**错发。落成租户可登记的举证表（POST /fulfillment-exceptions），"
                    + "每行强制来源枚举 + 采集时刻 + 主体；上游到位后由同步器按同一形状写入并改 "
                    + "reported_source。**没登记就是「未登记」，绝不画成「没有错发」**"),
            new QualityCapabilityDimension(
                "AFTER_SALE_DATA",
                "纠纷率 / 退货率（真实售后数据）",
                false,
                "卷 11 售后在**别的服务的独立库**里；AGENTS.md 禁止跨服务 SQL / 视图 / FK，"
                    + "因此本服务读不到真实售后数据",
                "11.1.1.1 / 11.1.1.2 / 11.2.1.1 售后（卷 11）",
                "落成租户可维护的信号表 fulfillment_quality_after_sale_signal"
                    + "（照抄 9.2.2.2 / V150 的既有解法）。"
                    + "**crossServiceReadAvailable=false、upstreamDelivered=false**，"
                    + "不让调用方从 404 或空数组里猜哪一环没通；正式接口到位后由同步器按"
                    + "同一形状写入，表结构与统计逻辑都不用改"),
            new QualityCapabilityDimension(
                "SHOP_CODE",
                "店铺维度到 shop_code 粒度",
                false,
                "sales_order 与 shipment_record 上**都没有 shop_code 列**（9.2.1.2 已核实）；"
                    + "本模块**为此不 ALTER 任何既有表**（跨车道 DDL 回归面必须为 0）",
                "D5 平台同步（店铺 / 账号同步）",
                "店铺维度当前**只能按平台级 order_source 判**（维度枚举 PLATFORM）。"
                    + "D5 落地后由同步器补 shop_code，**本枚举与统计逻辑都不用改**"),
            new QualityCapabilityDimension(
                "ACCOUNT_HEALTH_LINKAGE",
                "与 16 账号健康 / 17 绩效的联动预警",
                false,
                "16 账号健康与 17 绩效均未交付，集成头没有消费方",
                "16 合规账号健康 / 17 绩效",
                "本模块提供**租户可配置的预警阈值**（fulfillment_quality_alert_rule）与"
                    + "**append-only 预警留痕**（fulfillment_quality_alert）："
                    + "POST /alerts/evaluate 逐窗口 + 逐维度评估，越限即留痕。"
                    + "**不假装已对接 16**；16 到位后由它读本模块的授权 API")),
        Arrays.stream(QualityMetric.values())
            .map(m -> m.name() + ":" + m.displayName)
            .toList(),
        Arrays.stream(QualityMetric.values())
            .map(m -> m.name() + " | 分子=" + m.numerator + " | 分母=" + m.denominator + " | 时钟=" + m.clock)
            .toList(),
        Arrays.stream(QualityDimension.values()).map(d -> d.name() + ":" + d.displayName).toList(),
        "百分数，numeric(19,6)，0~100 量纲。比率一律由 SQL 的 "
            + "case when 分母=0 then null else round(分子*100.0/分母,6) end 现算，**不落表**"
            + "（落了表就会与事实漂移）",
        "**分母为 0 时 rate 返回 null**，绝不返回 0 或 1。分母为 0 的含义是"
            + "「这个窗口里没有任何可判定样本」；报成 0 会被读成「及时率 0%」—— 那是结论，不是缺口",
        "店铺维度当前**只能按平台级 order_source 判**（shopCodeAvailable=false）："
            + "sales_order 与 shipment_record 上都没有 shop_code 列（9.2.1.2 已核实），"
            + "且本模块不为它 ALTER 既有表。D5 落地后由同步器补 shop_code，统计逻辑不用改",
        "下游 17 绩效与 16 账号健康均未交付。本模块只提供租户可配置的阈值与 append-only 预警留痕，"
            + "**不冒充已对接 16**；纠纷率与错发漏发率是账号安全直接相关项，接上后由 16 消费");
  }

  // ================================================================ 窗口归一化

  /** 归一化后的实际窗口（内部用），与回显给调用方的 {@link QualityWindow} 同源。 */
  private record Window(OffsetDateTime from, OffsetDateTime to, boolean defaulted) {}

  /**
   * 统计窗口归一化。
   *
   * <p>规则（与 9.5.1.1 的 {@code BUSINESS_ZONE} 同一业务时区）：
   * <ul>
   *   <li>两端都不给 ⇒ 默认最近 {@value #DEFAULT_WINDOW_DAYS} 天，{@code defaulted=true}；</li>
   *   <li>只给一端 ⇒ 另一端取默认值；</li>
   *   <li>from ≥ to ⇒ 400，绝不静默交换（静默交换会让「查上个月」变成「查未来」）。</li>
   * </ul>
   *
   * <p>归一化后的<b>时刻值原样回显</b>，调用方能核对「我传的到底是哪一段」。
   * 全程 {@code OffsetDateTime}，不做时区转换 —— 转换会让同一段时间在不同时区给出不同的窗口。
   */
  private Window normalize(OffsetDateTime from, OffsetDateTime to) {
    boolean defaulted = from == null || to == null;
    OffsetDateTime now = OffsetDateTime.now(BUSINESS_ZONE);
    OffsetDateTime f =
        from != null
            ? from
            : (to != null ? to.minusDays(DEFAULT_WINDOW_DAYS) : now.minusDays(DEFAULT_WINDOW_DAYS));
    OffsetDateTime t = to != null ? to : now;
    if (!f.isBefore(t)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "from 必须早于 to（窗口不交换，from=" + f + " to=" + t + "）");
    }
    return new Window(f, t, defaulted);
  }

  private static QualityWindow echo(Window w) {
    return new QualityWindow(WINDOW_FMT.format(w.from()), WINDOW_FMT.format(w.to()), w.defaulted());
  }

  /** 每条查询共用的窗口 CTE 前缀。只绑定两个参数（纪律 5）。 */
  private static String winCte() {
    return "with win as (select ?::timestamptz as f, ?::timestamptz as t) ";
  }

  private static Object[] window(Window w, Object... rest) {
    Object[] out = new Object[rest.length + 2];
    out[0] = w.from();
    out[1] = w.to();
    System.arraycopy(rest, 0, out, 2, rest.length);
    return out;
  }

  // ================================================================ 维度片段

  /** 维度键表达式（同时充当分组键与展示标签来源，键即标签 ⇒ 不需要第二次查询）。 */
  private static String dimKey(QualityDimension dimension) {
    return dimension == QualityDimension.PERSON
        ? "coalesce(nullif(w.assignee_ref,''), '" + UNASSIGNED + "')"
        : "coalesce(nullif(qo.order_source,''), '" + UNATTACHED + "')";
  }

  /** 订单维度 join：所有指标都经它取 order_source 与 order_no。 */
  private static String dimJoins(MetricSpec s, QualityDimension dimension) {
    return " left join sales_order qo on qo.id = " + s.orderKey()
        + (dimension == QualityDimension.PERSON ? PERSON_JOIN : "");
  }

  /** 维度键 → 人可读标签。占位符是本模块自己定的，所以映射是封闭的。 */
  private static String dimLabel(QualityDimension dimension, String key) {
    if (UNASSIGNED.equals(key)) {
      return "（未认领）";
    }
    if (UNATTACHED.equals(key)) {
      return "（无订单归属）";
    }
    return key;
  }

  // ================================================================ 指标卡

  /** 单指标聚合的原始读数。rate 由 SQL 现算，Java 不参与除法。 */
  private record MetricRaw(long numerator, long denominator, long excluded, BigDecimal rate, long orders) {}

  private static MetricRaw raw(Record r) {
    return new MetricRaw(
        longOf(r, "num"),
        longOf(r, "den"),
        longOf(r, "exc"),
        r.get("rate", BigDecimal.class),
        longOf(r, "orders"));
  }

  /** 聚合查询：整租户（无维度）时 extraJoins 为空。 */
  private MetricRaw aggregate(MetricSpec s, Window w) {
    return aggregate(s, w, "", "");
  }

  private MetricRaw aggregate(MetricSpec s, Window w, String extraJoins, String whereExtra) {
    Record r =
        sql.fetchOne(
            winCte()
                + "select "
                + s.aggregates()
                + " from "
                + s.baseFrom()
                + extraJoins
                + " where "
                + s.drillClock()
                + " >= (select f from win) and "
                + s.drillClock()
                + " < (select t from win)"
                + whereExtra,
            window(w));
    return raw(r);
  }

  /**
   * 指标卡组装。<b>分子/分母/口径必须与 rate 同时出现</b>：
   * 只给一个百分数而不给分母，这个数字没法用来做任何判断。
   */
  private QualityMetricCard card(QualityMetric metric, MetricRaw raw) {
    return new QualityMetricCard(
        metric.name(),
        metric.displayName,
        metric.numerator,
        metric.denominator,
        raw.rate(),
        raw.numerator(),
        raw.denominator(),
        raw.excluded(),
        false,
        null,
        metric.direction.name(),
        metric.clock,
        "分子(" + metric.numerator + ") ÷ 分母(" + metric.denominator + ")，百分数保留 6 位小数",
        metric.caveat,
        "drilldown?metric=" + metric.name());
  }

  /** 指标卡总览：六个指标 + 覆盖量（哪些订单根本没测过）。 */
  public QualityReport report(OffsetDateTime from, OffsetDateTime to) {
    Window w = normalize(from, to);
    List<QualityMetricCard> cards = new ArrayList<>();
    for (QualityMetric m : QualityMetric.values()) {
      MetricSpec s = spec(m);
      String joins = " left join sales_order qo on qo.id = " + s.orderKey();
      cards.add(card(m, aggregate(s, w, joins, "")));
    }

    long shippedOrders =
        count(
            winCte()
                + "select count(distinct sales_order_id) from shipment_record"
                + " where shipped_at >= (select f from win) and shipped_at < (select t from win)"
                + "   and sales_order_id is not null",
            window(w));
    long submittedOrders =
        count(
            winCte()
                + "select count(*) from sales_order"
                + " where submitted_at >= (select f from win) and submitted_at < (select t from win)",
            window(w));
    long ordersWithProgress =
        count(
            winCte()
                + "select count(distinct p.order_id) from order_fulfillment_line_progress p"
                + " where p.shipped_at >= (select f from win) and p.shipped_at < (select t from win)",
            window(w));

    return new QualityReport(
        cards,
        echo(w),
        shippedOrders,
        submittedOrders,
        "窗口内已提交订单 " + submittedOrders + " 单 / 已有交接事实 " + shippedOrders
            + " 单 / 有履约进度投影 " + ordersWithProgress + " 单",
        "三个分母**刻意各不相同**（已提交 / 已交接 / 有进度投影），因为六个指标问的不是同一批单："
            + "压成一个分母会让「没有可判定样本的那一类」被算成 0%。"
            + "窗口内已提交但没有任何进度投影的订单 " + Math.max(0, submittedOrders - ordersWithProgress)
            + " 单，它们不是「不完整」而是「没测过」");
  }

  // ================================================================ 维度对比

  /**
   * 按店铺（平台级）或人员维度对比。
   *
   * <p>两个维度都用<b>真 GROUP BY</b>；每个维度值下<b>六个指标全量</b>给出，
   * 便于横向比而不是一次只看一个指标。
   *
   * <p>人员维度走 {@link #PERSON_JOIN}（一人多单地展开），所以聚合一律
   * {@code count(distinct 计量单元)} —— 展开不会放大计数。
   */
  public QualityDimensionPage dimensions(
      QualityDimension dimension, OffsetDateTime from, OffsetDateTime to, int limit) {
    Window w = normalize(from, to);
    int cap = limit > 0 ? Math.min(limit, MAX_PAGE_SIZE) : 50;
    String key = dimKey(dimension);

    // 六次分组查询的结果按维度键横向拼成行
    Map<String, List<QualityMetricCard>> byKey = new LinkedHashMap<>();
    for (QualityMetric m : QualityMetric.values()) {
      MetricSpec s = spec(m);
      List<Record> rows =
          sql.fetch(
              winCte()
                  + "select "
                  + key
                  + " as dim_key, "
                  + s.aggregates()
                  + " from "
                  + s.baseFrom()
                  + dimJoins(s, dimension)
                  + " where "
                  + s.drillClock()
                  + " >= (select f from win) and "
                  + s.drillClock()
                  + " < (select t from win)"
                  + " group by 1"
                  + " order by den desc, dim_key"
                  + " limit ?",
              window(w, cap));
      for (Record r : rows) {
        String k = text(r.get("dim_key"));
        byKey.computeIfAbsent(k, x -> new ArrayList<>());
        byKey.get(k).add(card(m, raw(r)));
      }
    }

    // 每一维都必须给满六张卡：某指标没覆盖到的维度值也要出现（分母 0 ⇒ rate null）
    List<QualityDimensionRow> items = new ArrayList<>();
    for (Map.Entry<String, List<QualityMetricCard>> e : byKey.entrySet()) {
      List<QualityMetricCard> rowCards = e.getValue();
      for (QualityMetric m : QualityMetric.values()) {
        boolean present =
            rowCards.stream().anyMatch(c -> c.metricCode().equals(m.name()));
        if (!present) {
          rowCards.add(card(m, new MetricRaw(0, 0, 0, null, 0)));
        }
      }
      items.add(
          new QualityDimensionRow(
              dimension.name(),
              e.getKey(),
              dimLabel(dimension, e.getKey()),
              ordersOf(rowCards),
              rowCards,
              dimensionCaveat(dimension)));
    }

    return new QualityDimensionPage(
        dimension.name(),
        dimension.displayName,
        items,
        items.size(),
        dimension == QualityDimension.PERSON
            ? "认领人由仓库侧作业系统回填，空值很多是正常现状，不是数据错误"
            : "平台级口径；D5 落地后由同步器补 shop_code，本维度与统计逻辑都不用改");
  }

  /** 维度行的订单覆盖量取六个指标里的最大值（各指标分母集合不同）。 */
  private static long ordersOf(List<QualityMetricCard> cards) {
    long max = 0;
    for (QualityMetricCard c : cards) {
      max = Math.max(max, c.numeratorCount());
    }
    return max;
  }

  private static String dimensionCaveat(QualityDimension dimension) {
    return dimension == QualityDimension.PERSON
        ? "人员维度来自 fulfillment_request_work_order.assignee_ref（仓库侧作业系统回填的认领人），"
            + "经 fulfillment_request.order_ref 对齐 sales_order.order_no —— order_ref 是 9.3.3.1 明确声明"
            + "「只存引用不 JOIN」的逻辑引用列，本模块按租户内唯一的 order_no 文本对齐。"
            + "认不上的落「未认领」独立桶；一票货多人参与时**同时**落到每个人下面（表达「涉及该人员的订单」），"
            + "因此各行分母之和可以大于订单总数"
        : "店铺维度当前**只能到平台级 order_source**（shopCodeAvailable=false）：sales_order 与 "
            + "shipment_record 上都没有 shop_code 列，且本模块不为它 ALTER 既有表。"
            + "「无订单归属」独立成桶，不并进任何有值分组";
  }

  // ================================================================ 明细下钻

  /**
   * 指标明细下钻：<b>构成该指标的明细单</b>，不是只有数字。
   *
   * <p>同一张订单可能既在分子也在分母里（{@code metricRole} 区分）：
   * 发货及时率里 UNSET 的票不进分母但**必须看得见** ——
   * 一个只列分母成员的清单会让人以为「没时限的票不存在」。
   */
  public QualityDrilldownPage drilldown(
      QualityMetric metric,
      String dimensionBy,
      String dimensionValue,
      QualitySort sort,
      OffsetDateTime from,
      OffsetDateTime to,
      int page,
      int size) {
    Window w = normalize(from, to);
    int p = Math.max(page, 0);
    int s = size <= 0 ? 50 : Math.min(size, MAX_PAGE_SIZE);
    MetricSpec spec = spec(metric);

    QualityDimension dimension = null;
    if (!blank(dimensionBy)) {
      try {
        dimension = QualityDimension.valueOf(dimensionBy.toUpperCase(Locale.ROOT));
      } catch (IllegalArgumentException e) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "dimensionBy 只能是 PLATFORM / PERSON");
      }
    }
    String joins = " left join sales_order qo on qo.id = " + spec.orderKey();
    if (dimension == QualityDimension.PERSON) {
      joins += PERSON_JOIN;
    }
    String dimClause = "";
    List<Object> dimParams = new ArrayList<>();
    if (dimension != null && !blank(dimensionValue)) {
      String key = dimKey(dimension);
      // 占位符反查成真实空值，否则筛「（未认领）」永远筛不到
      if (UNASSIGNED.equals(dimensionValue) || UNATTACHED.equals(dimensionValue)) {
        String col = dimension == QualityDimension.PERSON ? "w.assignee_ref" : "qo.order_source";
        dimClause = " and coalesce(nullif(" + col + ",''),'') = ''";
      } else {
        dimClause = " and " + key + " = ?::text";
        dimParams.add(dimensionValue);
      }
    }

    // 内层：一个指标计量单元一行。
    // 刻意用 distinct on(<计量单元>) 而不是「每源表一行」：按订单计的两个指标
    // （错发漏发 / 售后）源表是「订单行」，一单两行会出两行，
    // 下钻清单的条数就会与分母对不上 —— 一个对不上数的清单没人敢用。
    String inner =
        winCte()
            + "select distinct on ("
            + spec.unit()
            + ") qo.id as order_id, coalesce(qo.order_no,'') as order_no,"
            + " coalesce(qo.order_source,'') as order_source,"
            + " case when ("
            + spec.numPredicate()
            + ") then 'NUMERATOR' else 'DENOMINATOR_ONLY' end as metric_role,"
            + " cast("
            + spec.drillClass()
            + " as varchar) as classification,"
            + spec.drillClock()
            + " as occurred_at,"
            + " null::numeric(19,6) as quantity,"
            + " null::numeric(19,6) as required_quantity,"
            + " '"
            + spec.drillKind()
            + "'::text as detail_kind,"
            + " '"
            + spec.unit()
            + "'::text as detail_ref,"
            + " coalesce(qo.order_no,'') as detail_no"
            + " from "
            + spec.baseFrom()
            + joins
            + " where "
            + spec.drillClock()
            + " >= (select f from win) and "
            + spec.drillClock()
            + " < (select t from win)"
            + " and ("
            + spec.denPredicate()
            + " or "
            + spec.excPredicate()
            + ")"
            + dimClause
            + " order by "
            + spec.unit()
            + ", "
            + spec.drillClock()
            + " asc";

    List<Object> args = new ArrayList<>();
    args.add(w.from());
    args.add(w.to());
    args.addAll(dimParams);

    long total =
        count("select count(*) from (" + inner + ") u", args.toArray());
    List<Record> rows =
        sql.fetch(
            "select * from (" + inner + ") u order by " + sortSql(sort) + " limit ? offset ?",
            concat(args, s, (long) p * s));

    List<QualityDrilldownItem> items = new ArrayList<>();
    for (Record r : rows) {
      String classification = text(r.get("classification"));
      items.add(
          new QualityDrilldownItem(
              r.get("order_id", UUID.class),
              text(r.get("order_no")),
              text(r.get("order_source")),
              "",
              "",
              text(r.get("metric_role")),
              classification,
              drillClassLabel(classification),
              r.get("occurred_at", OffsetDateTime.class),
              r.get("quantity", BigDecimal.class),
              r.get("required_quantity", BigDecimal.class),
              text(r.get("detail_kind")),
              text(r.get("detail_ref")),
              text(r.get("detail_no"))));
    }

    MetricRaw totals = aggregate(spec, w, " left join sales_order qo on qo.id = " + spec.orderKey(), "");
    return new QualityDrilldownPage(
        metric.name(),
        metric.displayName,
        "分子(" + metric.numerator + ") ÷ 分母(" + metric.denominator + ")",
        items,
        p,
        s,
        total,
        totalPages(total, s),
        totals.numerator(),
        totals.denominator(),
        totals.rate(),
        metric.caveat + "。明细里的 DENOMINATOR_ONLY 行包括**刻意不进分母**的样本（如未登记发货时限），"
            + "它们必须可见：一个只列分母成员的清单会让人以为「没时限的票不存在」");
  }

  /** 下钻分类 → 人可读标签。词表是本模块自己定的，所以映射封闭。 */
  private static String drillClassLabel(String classification) {
    return switch (classification) {
      case "ON_TIME" -> "按时发货";
      case "OVERDUE" -> "超出发货时限";
      case "UNSET" -> "未登记发货时限（不进分母）";
      case "COMPLETE" -> "发全";
      case "INCOMPLETE" -> "未发全";
      case "WRONG_ITEM" -> "错发（已登记）";
      case "MISSING_ITEM_DECLARED" -> "漏发（已登记）";
      case "MISSING_ITEM_DERIVED" -> "漏发（系统按已发量 < 下单量推导）";
      case "CANCELLED" -> "已确认取消";
      case "NOT_CANCELLED" -> "未取消";
      case "DISPUTED" -> "有纠纷";
      case "RETURNED" -> "有退货";
      case "CLEAN" -> "窗口内无该类信号";
      default -> classification;
    };
  }

  private static String sortSql(QualitySort sort) {
    return switch (sort) {
      case OCCURRED_AT_DESC -> "occurred_at desc, order_no asc";
      case OCCURRED_AT_ASC -> "occurred_at asc, order_no asc";
      case ORDER_NO_ASC -> "order_no asc, occurred_at desc";
      case QUANTITY_DESC -> "quantity desc nulls last, order_no asc";
    };
  }

  // ================================================================ 售后信号

  /** 登记一笔纠纷 / 退货（11 售后未交付，逐笔登记而非伪造读取）。 */
  @Transactional
  public AfterSaleSignalRow registerAfterSaleSignal(AfterSaleSignalInput input, UUID membership) {
    UUID tenant = currentTenant();
    UUID orderId = input.orderId();
    // 刻意先把订单号读出来再插，而不是在 values 里写 (select order_no ...)：
    // 本仓库的 jOOQ 对**子查询里的占位符**类型推断不出来，绑定会直接炸
    // （bad SQL grammar）。多一次往返，换来一条可审阅的显式 INSERT。
    Record order = sql.fetchOne("select order_no from sales_order where id = ?", orderId);
    if (order == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "订单不存在或不属于当前租户");
    }
    String orderNo = text(order.get("order_no"));
    OffsetDateTime now = OffsetDateTime.now(BUSINESS_ZONE);
    AfterSaleSignalState state = input.signalState() == null ? AfterSaleSignalState.OPEN : input.signalState();
    String source = "TENANT_MAINTAINED";
    String reference = input.sourceReference() == null ? "" : input.sourceReference().trim();
    if (!reference.isEmpty()) {
      // 有外部引用号说明这条来自外部同步而不是人工登记：来源如实标成同步器，
      // 免得「同步进来的」被算成「人工报的」。
      source = input.signalType() == AfterSaleSignalType.DISPUTE ? "MODULE_11_DISPUTE" : "MODULE_11_RETURN";
    }

    UUID id = UUID.randomUUID();
    sql.execute(
        "insert into fulfillment_quality_after_sale_signal"
            + " (id,tenant_id,order_id,order_no,platform_order_no,signal_type,signal_state,"
            + "  occurred_at,signal_source,source_reference,captured_at,reported_by_membership_id,note)"
            + " values (?,?,?,?,?,?,?,?::timestamptz,?,?,?::timestamptz,?,?)",
        id,
        tenant,
        orderId,
        orderNo,
        input.platformOrderNo() == null ? "" : input.platformOrderNo().trim(),
        input.signalType().name(),
        state.name(),
        input.occurredAt(),
        source,
        reference,
        now,
        membership,
        input.note() == null ? "" : input.note());
    appendEvent(tenant, "AFTER_SALE_SIGNAL_REGISTERED",
        input.signalType().displayName + " 登记于订单 " + orderId, orderId, null, membership, now);
    return afterSaleSignalRow(id);
  }

  private AfterSaleSignalRow afterSaleSignalRow(UUID id) {
    Record r =
        sql.fetchOne(
            "select id,order_id,order_no,signal_type,signal_state,platform_order_no,occurred_at,"
                + " signal_source,source_reference,captured_at,note"
                + " from fulfillment_quality_after_sale_signal where id = ?",
            id);
    return new AfterSaleSignalRow(
        r.get("id", UUID.class),
        r.get("order_id", UUID.class),
        text(r.get("order_no")),
        text(r.get("signal_type")),
        text(r.get("signal_state")),
        text(r.get("platform_order_no")),
        r.get("occurred_at", OffsetDateTime.class),
        text(r.get("signal_source")),
        text(r.get("source_reference")),
        r.get("captured_at", OffsetDateTime.class),
        text(r.get("note")));
  }

  /** 售后信号清单（真分页 + 汇总）。 */
  public AfterSaleSignalPage afterSaleSignals(
      String signalType, String signalState, String keyword, int page, int size) {
    int p = Math.max(page, 0);
    int s = size <= 0 ? 50 : Math.min(size, MAX_PAGE_SIZE);
    Params f = new Params();
    if (!blank(signalType)) {
      f.clause.append(" and g.signal_type = ?::varchar");
      f.params.add(signalType);
    }
    if (!blank(signalState)) {
      f.clause.append(" and g.signal_state = ?::varchar");
      f.params.add(signalState);
    }
    if (!blank(keyword)) {
      String like = "%" + keyword.trim().toLowerCase(Locale.ROOT) + "%";
      f.clause.append(" and (lower(g.order_no) like ?::varchar or lower(g.note) like ?::varchar"
          + " or lower(g.source_reference) like ?::varchar)");
      f.params.add(like);
      f.params.add(like);
      f.params.add(like);
    }
    long total =
        count("select count(*) from fulfillment_quality_after_sale_signal g where true" + f.clause, f.params);
    List<Record> rows =
        sql.fetch(
            "select g.id,g.order_id,g.order_no,g.signal_type,g.signal_state,g.platform_order_no,"
                + " g.occurred_at,g.signal_source,g.source_reference,g.captured_at,g.note"
                + " from fulfillment_quality_after_sale_signal g where true"
                + f.clause
                + " order by g.occurred_at desc, g.id desc"
                + " limit ? offset ?",
            concat(f.params, s, (long) p * s));

    List<AfterSaleSignalRow> items = new ArrayList<>();
    for (Record r : rows) {
      items.add(
          new AfterSaleSignalRow(
              r.get("id", UUID.class),
              r.get("order_id", UUID.class),
              text(r.get("order_no")),
              text(r.get("signal_type")),
              text(r.get("signal_state")),
              text(r.get("platform_order_no")),
              r.get("occurred_at", OffsetDateTime.class),
              text(r.get("signal_source")),
              text(r.get("source_reference")),
              r.get("captured_at", OffsetDateTime.class),
              text(r.get("note"))));
    }
    Record counts =
        sql.fetchOne(
            "select count(*) filter (where g.signal_type='DISPUTE') as dispute,"
                + " count(*) filter (where g.signal_type='RETURN') as returns"
                + " from fulfillment_quality_after_sale_signal g where true"
                + f.clause,
            f.params.toArray());
    return new AfterSaleSignalPage(
        items, p, s, total, totalPages(total, s), longOf(counts, "dispute"), longOf(counts, "returns"));
  }

  // ================================================================ 履约缺陷

  /** 登记一次错发 / 漏发（append-only 举证凭据）。 */
  @Transactional
  public FulfillmentExceptionRow registerException(FulfillmentExceptionInput input, UUID membership) {
    UUID tenant = currentTenant();
    // 同上：先把订单号读出来，不在 values 里写子查询占位符。
    Record order = sql.fetchOne("select order_no from sales_order where id = ?", input.orderId());
    if (order == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "订单不存在或不属于当前租户");
    }
    String reference = input.sourceReference() == null ? "" : input.sourceReference().trim();
    String source = reference.isEmpty() ? "TENANT_MAINTAINED" : "MODULE_9_4_FEEDBACK";
    UUID id = UUID.randomUUID();
    sql.execute(
        "insert into fulfillment_quality_fulfillment_exception"
            + " (id,tenant_id,order_id,order_no,order_line_id,line_no,sku_code,exception_type,"
            + "  affected_qty,reported_at,reported_source,source_reference,reported_by_membership_id,note)"
            + " values (?,?,?,?,?,?::int,?,?,?::numeric,?::timestamptz,?,?,?,?)",
        id,
        tenant,
        input.orderId(),
        text(order.get("order_no")),
        input.orderLineId(),
        input.lineNo(),
        input.skuCode() == null ? "" : input.skuCode().trim(),
        input.exceptionType().name(),
        input.affectedQty(),
        input.reportedAt(),
        source,
        reference,
        membership,
        input.note() == null ? "" : input.note());
    appendEvent(tenant, "FULFILLMENT_EXCEPTION_REGISTERED",
        input.exceptionType().displayName + " 登记于订单 " + input.orderId(),
        input.orderId(), null, membership, input.reportedAt());
    return exceptionRow(id);
  }

  private FulfillmentExceptionRow exceptionRow(UUID id) {
    Record r =
        sql.fetchOne(
            "select x.id,x.order_id,x.order_no,x.order_line_id,x.sku_code,x.exception_type,"
                + " x.affected_qty,x.reported_at,x.reported_source,x.source_reference,x.note,"
                + " o.order_source"
                + " from fulfillment_quality_fulfillment_exception x"
                + " left join sales_order o on o.id = x.order_id"
                + " where x.id = ?",
            id);
    return new FulfillmentExceptionRow(
        r.get("id", UUID.class),
        r.get("order_id", UUID.class),
        text(r.get("order_no")),
        text(r.get("order_source")),
        r.get("order_line_id", UUID.class),
        text(r.get("sku_code")),
        text(r.get("exception_type")),
        r.get("affected_qty", BigDecimal.class),
        r.get("reported_at", OffsetDateTime.class),
        text(r.get("reported_source")),
        text(r.get("source_reference")),
        text(r.get("note")));
  }

  /** 履约缺陷清单（真分页 + 汇总）。 */
  public FulfillmentExceptionPage exceptions(String exceptionType, String keyword, int page, int size) {
    int p = Math.max(page, 0);
    int s = size <= 0 ? 50 : Math.min(size, MAX_PAGE_SIZE);
    Params f = new Params();
    if (!blank(exceptionType)) {
      f.clause.append(" and x.exception_type = ?::varchar");
      f.params.add(exceptionType);
    }
    if (!blank(keyword)) {
      String like = "%" + keyword.trim().toLowerCase(Locale.ROOT) + "%";
      f.clause.append(" and (lower(x.order_no) like ?::varchar or lower(x.sku_code) like ?::varchar"
          + " or lower(x.note) like ?::varchar)");
      f.params.add(like);
      f.params.add(like);
      f.params.add(like);
    }
    long total =
        count(
            "select count(*) from fulfillment_quality_fulfillment_exception x where true" + f.clause,
            f.params);
    List<Record> rows =
        sql.fetch(
            "select x.id,x.order_id,x.order_no,x.order_line_id,x.sku_code,x.exception_type,"
                + " x.affected_qty,x.reported_at,x.reported_source,x.source_reference,x.note,"
                + " o.order_source"
                + " from fulfillment_quality_fulfillment_exception x"
                + " left join sales_order o on o.id = x.order_id"
                + " where true"
                + f.clause
                + " order by x.reported_at desc, x.id desc"
                + " limit ? offset ?",
            concat(f.params, s, (long) p * s));

    List<FulfillmentExceptionRow> items = new ArrayList<>();
    for (Record r : rows) {
      items.add(
          new FulfillmentExceptionRow(
              r.get("id", UUID.class),
              r.get("order_id", UUID.class),
              text(r.get("order_no")),
              text(r.get("order_source")),
              r.get("order_line_id", UUID.class),
              text(r.get("sku_code")),
              text(r.get("exception_type")),
              r.get("affected_qty", BigDecimal.class),
              r.get("reported_at", OffsetDateTime.class),
              text(r.get("reported_source")),
              text(r.get("source_reference")),
              text(r.get("note"))));
    }
    Record counts =
        sql.fetchOne(
            "select count(*) filter (where x.exception_type='WRONG_ITEM') as wrong,"
                + " count(*) filter (where x.exception_type='MISSING_ITEM') as missing"
                + " from fulfillment_quality_fulfillment_exception x where true"
                + f.clause,
            f.params.toArray());
    return new FulfillmentExceptionPage(
        items, p, s, total, totalPages(total, s), longOf(counts, "wrong"), longOf(counts, "missing"));
  }

  // ================================================================ 预警阈值

  /** 阈值清单（六条上限，缺行即「该指标还没配阈值」而不是 0）。 */
  public List<AlertRuleRow> alertRules() {
    List<Record> rows =
        sql.fetch(
            "select id,metric_code,threshold_value,comparison,severity,enabled,note,updated_at"
                + " from fulfillment_quality_alert_rule order by metric_code");
    List<AlertRuleRow> out = new ArrayList<>();
    for (Record r : rows) {
      String code = text(r.get("metric_code"));
      out.add(
          new AlertRuleRow(
              r.get("id", UUID.class),
              code,
              metricDisplay(code),
              r.get("threshold_value", BigDecimal.class),
              text(r.get("comparison")),
              text(r.get("severity")),
              Boolean.TRUE.equals(r.get("enabled", Boolean.class)),
              text(r.get("note")),
              r.get("updated_at", OffsetDateTime.class)));
    }
    return out;
  }

  /** 阈值配置（幂等 upsert：一个指标一条生效规则）。 */
  @Transactional
  public AlertRuleRow upsertAlertRule(QualityMetric metric, AlertRuleInput input, UUID membership) {
    UUID tenant = currentTenant();
    boolean enabled = input.enabled() == null || input.enabled();
    AlertSeverity severity = input.severity() == null ? AlertSeverity.WARN : input.severity();
    sql.execute(
        "insert into fulfillment_quality_alert_rule"
            + " (id,tenant_id,metric_code,threshold_value,comparison,severity,enabled,note,"
            + "  created_by_membership_id,created_at,updated_at)"
            + " values (?,?,?,?::numeric,?,?,?,?,?,now(),now())"
            + " on conflict (tenant_id,metric_code) do update set"
            + "  threshold_value = excluded.threshold_value, comparison = excluded.comparison,"
            + "  severity = excluded.severity, enabled = excluded.enabled, note = excluded.note,"
            + "  updated_at = now()",
        UUID.randomUUID(),
        tenant,
        metric.name(),
        input.thresholdValue(),
        input.comparison().name(),
        severity.name(),
        enabled,
        input.note() == null ? "" : input.note(),
        membership);
    appendEvent(tenant, "ALERT_RULE_UPSERTED", metric.displayName + " 阈值设为 " + input.thresholdValue(),
        null, null, membership, OffsetDateTime.now(BUSINESS_ZONE));
    Record r =
        sql.fetchOne(
            "select id,metric_code,threshold_value,comparison,severity,enabled,note,updated_at"
                + " from fulfillment_quality_alert_rule where metric_code = ?",
            metric.name());
    return new AlertRuleRow(
        r.get("id", UUID.class),
        metric.name(),
        metric.displayName,
        r.get("threshold_value", BigDecimal.class),
        text(r.get("comparison")),
        text(r.get("severity")),
        Boolean.TRUE.equals(r.get("enabled", Boolean.class)),
        text(r.get("note")),
        r.get("updated_at", OffsetDateTime.class));
  }

  /**
   * 逐窗口（可选逐维度）评估预警。
   *
   * <p><b>分母为 0 时不产生预警</b>：没有样本不是指标变差，
   * 把「算不出来」推成「恶化」才是预警体系最危险的错。
   * 这条同时被 DB 触发器 guard_fulfillment_quality_alert_basis 钉死。
   *
   * <p>幂等：唯一键 (rule, window, dimension) 冲突时跳过，重放评估不会堆出一串重复预警。
   */
  @Transactional
  public AlertEvaluateResult evaluateAlerts(AlertEvaluateInput input) {
    Window w = normalize(input.from(), input.to());
    UUID tenant = currentTenant();
    boolean perDimension = input.perDimension() != null && input.perDimension();
    int evaluated = 0;
    int triggered = 0;
    int skippedNoBasis = 0;

    List<Record> rules =
        sql.fetch(
            "select id,metric_code,threshold_value,comparison,severity"
                + " from fulfillment_quality_alert_rule where enabled");
    for (Record rule : rules) {
      String code = text(rule.get("metric_code"));
      QualityMetric metric;
      try {
        metric = QualityMetric.valueOf(code);
      } catch (IllegalArgumentException e) {
        continue;
      }
      MetricSpec s = spec(metric);
      MetricRaw overall =
          aggregate(s, w, " left join sales_order qo on qo.id = " + s.orderKey(), "");

      List<String[]> targets = new ArrayList<>();
      targets.add(new String[] {"", ""});
      if (perDimension) {
        for (QualityDimension d : QualityDimension.values()) {
          for (QualityDimensionRow row : dimensions(d, w.from(), w.to(), MAX_PAGE_SIZE).items()) {
            QualityMetricCard c =
                row.metrics().stream()
                    .filter(x -> x.metricCode().equals(code))
                    .findFirst()
                    .orElse(null);
            if (c == null) {
              continue;
            }
            evaluated++;
            if (c.denominatorCount() == 0) {
              skippedNoBasis++;
              continue;
            }
            int inserted =
                maybeAlert(tenant, rule, metric, c, d.name(), row.dimensionKey(), w);
            triggered += inserted;
          }
        }
      }

      evaluated++;
      if (overall.denominator() == 0) {
        skippedNoBasis++;
      } else {
        QualityMetricCard c = card(metric, overall);
        triggered += maybeAlert(tenant, rule, metric, c, "", "", w);
      }
    }
    return new AlertEvaluateResult(
        evaluated, triggered, skippedNoBasis,
        WINDOW_FMT.format(w.from()), WINDOW_FMT.format(w.to()), perDimension);
  }

  /** 越限才落一条预警；未越限或已存在同键预警时返回 0。 */
  private int maybeAlert(
      UUID tenant,
      Record rule,
      QualityMetric metric,
      QualityMetricCard c,
      String dimensionCode,
      String dimensionValue,
      Window w) {
    int matched = compare(c.rate(), r(rule, "threshold_value"), AlertComparison.valueOf(text(r(rule, "comparison"))));
    if (matched == 0) {
      return 0;
    }
    UUID alertId = UUID.randomUUID();
    int inserted =
        sql.execute(
            "insert into fulfillment_quality_alert"
                + " (id,tenant_id,rule_id,metric_code,observed_value,threshold_value,comparison,"
                + "  severity,numerator,denominator,window_from,window_to,dimension_code,"
                + "  dimension_value,triggered_at)"
                + " values (?,?,?,?,?::numeric,?::numeric,?,?,?,?,?::timestamptz,?::timestamptz,?,?,?::timestamptz)"
                + " on conflict (tenant_id,rule_id,window_from,window_to,dimension_code,dimension_value)"
                + " do nothing",
            alertId,
            tenant,
            rUuid(rule, "id"),
            metric.name(),
            c.rate(),
            toDecimal(r(rule, "threshold_value")),
            text(r(rule, "comparison")),
            text(r(rule, "severity")),
            c.numeratorCount(),
            c.denominatorCount(),
            w.from(),
            w.to(),
            dimensionCode,
            dimensionValue,
            OffsetDateTime.now(BUSINESS_ZONE));
    if (inserted > 0) {
      appendEvent(tenant, "ALERT_TRIGGERED",
          metric.displayName + " 观测 " + c.rate() + " 越过阈值 "
              + toDecimal(r(rule, "threshold_value")),
          null, alertId, null, OffsetDateTime.now(BUSINESS_ZONE));
    }
    return inserted;
  }

  /** 比较方向必须显式（纪律：方向反了阈值会反过来报警）。返回 1 = 越限。 */
  private static int compare(BigDecimal observed, Object threshold, AlertComparison comparison) {
    if (observed == null) {
      return 0;
    }
    BigDecimal t = toDecimal(threshold);
    if (t == null) {
      return 0;
    }
    int c = observed.compareTo(t);
    return switch (comparison) {
      case GT -> c > 0 ? 1 : 0;
      case GTE -> c >= 0 ? 1 : 0;
      case LT -> c < 0 ? 1 : 0;
      case LTE -> c <= 0 ? 1 : 0;
    };
  }

  /** 确认一条预警。append-only ⇒ 确认落**事件**，不改预警行本身。 */
  @Transactional
  public boolean acknowledgeAlert(String id, UUID membership) {
    UUID alertId = parseUuid(id);
    Record a =
        sql.fetchOne(
            "select id from fulfillment_quality_alert where id = ?", alertId);
    if (a == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "预警不存在或不属于当前租户");
    }
    boolean already =
        sql.fetchOne(
                "select 1 from fulfillment_quality_event"
                    + " where alert_id = ? and kind = 'ALERT_ACKNOWLEDGED'",
                alertId)
            != null;
    if (already) {
      return false;
    }
    appendEvent(currentTenant(), "ALERT_ACKNOWLEDGED", "预警已确认", null, alertId, membership,
        OffsetDateTime.now(BUSINESS_ZONE));
    return true;
  }

  /** 预警清单（真分页；汇总给出未确认数）。 */
  public AlertPage alerts(String metricCode, String dimensionCode, int page, int size) {
    int p = Math.max(page, 0);
    int s = size <= 0 ? 50 : Math.min(size, MAX_PAGE_SIZE);
    Params f = new Params();
    if (!blank(metricCode)) {
      f.clause.append(" and a.metric_code = ?::varchar");
      f.params.add(metricCode);
    }
    if (!blank(dimensionCode)) {
      f.clause.append(" and a.dimension_code = ?::varchar");
      f.params.add(dimensionCode);
    }
    long total =
        count("select count(*) from fulfillment_quality_alert a where true" + f.clause, f.params);
    List<Record> rows =
        sql.fetch(
            "select a.id,a.metric_code,a.observed_value,a.threshold_value,a.comparison,a.severity,"
                + " a.numerator,a.denominator,a.window_from,a.window_to,a.dimension_code,"
                + " a.dimension_value,a.triggered_at,"
                + " exists(select 1 from fulfillment_quality_event e"
                + "   where e.alert_id = a.id and e.kind = 'ALERT_ACKNOWLEDGED') as acknowledged"
                + " from fulfillment_quality_alert a where true"
                + f.clause
                + " order by a.triggered_at desc, a.id desc"
                + " limit ? offset ?",
            concat(f.params, s, (long) p * s));
    List<AlertRow> items = new ArrayList<>();
    for (Record r : rows) {
      String code = text(r.get("metric_code"));
      items.add(
          new AlertRow(
              r.get("id", UUID.class),
              code,
              metricDisplay(code),
              r.get("observed_value", BigDecimal.class),
              r.get("threshold_value", BigDecimal.class),
              text(r.get("comparison")),
              text(r.get("severity")),
              longOf(r, "numerator"),
              longOf(r, "denominator"),
              WINDOW_FMT.format(r.get("window_from", OffsetDateTime.class)),
              WINDOW_FMT.format(r.get("window_to", OffsetDateTime.class)),
              text(r.get("dimension_code")),
              text(r.get("dimension_value")),
              r.get("triggered_at", OffsetDateTime.class),
              Boolean.TRUE.equals(r.get("acknowledged", Boolean.class))));
    }
    long open =
        count(
            "select count(*) from fulfillment_quality_alert a where true"
                + f.clause
                + " and not exists(select 1 from fulfillment_quality_event e"
                + "   where e.alert_id = a.id and e.kind = 'ALERT_ACKNOWLEDGED')",
            f.params);
    return new AlertPage(items, p, s, total, totalPages(total, s), open);
  }

  // ================================================================ 事件流

  /** 事件流（append-only，真分页）。 */
  public QualityEventPage events(String kind, String orderId, int page, int size) {
    int p = Math.max(page, 0);
    int s = size <= 0 ? 50 : Math.min(size, MAX_PAGE_SIZE);
    Params f = new Params();
    if (!blank(kind)) {
      f.clause.append(" and e.kind = ?::varchar");
      f.params.add(kind);
    }
    if (!blank(orderId)) {
      f.clause.append(" and e.order_id = ?");
      f.params.add(parseUuid(orderId));
    }
    long total = count("select count(*) from fulfillment_quality_event e where true" + f.clause, f.params);
    List<Record> rows =
        sql.fetch(
            "select e.seq,e.kind,e.summary,e.order_id,e.alert_id,e.occurred_at"
                + " from fulfillment_quality_event e where true"
                + f.clause
                + " order by e.seq desc"
                + " limit ? offset ?",
            concat(f.params, s, (long) p * s));
    List<QualityEventRow> items = new ArrayList<>();
    for (Record r : rows) {
      items.add(
          new QualityEventRow(
              longOf(r, "seq"),
              text(r.get("kind")),
              text(r.get("summary")),
              r.get("order_id", UUID.class),
              r.get("alert_id", UUID.class),
              r.get("occurred_at", OffsetDateTime.class)));
    }
    return new QualityEventPage(items, p, s, total, totalPages(total, s));
  }

  // ================================================================ 内部工具

  private void appendEvent(
      UUID tenant, String kind, String summary, UUID orderId, UUID alertId, UUID membership, OffsetDateTime when) {
    sql.execute(
        "insert into fulfillment_quality_event"
            + " (id,tenant_id,kind,summary,order_id,alert_id,actor_membership_id,occurred_at)"
            + " values (?,?,?,?,?,?,?,?::timestamptz)",
        UUID.randomUUID(),
        tenant,
        kind,
        summary.length() > 300 ? summary.substring(0, 300) : summary,
        orderId,
        alertId,
        membership,
        when);
  }

  private static String metricDisplay(String code) {
    try {
      return QualityMetric.valueOf(code).displayName;
    } catch (IllegalArgumentException e) {
      return code;
    }
  }

  private static Object r(Record rec, String column) {
    return rec.get(column);
  }

  private static UUID rUuid(Record rec, String column) {
    return rec.get(column, UUID.class);
  }

  private static BigDecimal toDecimal(Object o) {
    if (o == null) {
      return null;
    }
    if (o instanceof BigDecimal b) {
      return b;
    }
    return new BigDecimal(o.toString());
  }

  private UUID currentTenant() {
    return sql.fetchOne("select nullif(current_setting('app.current_tenant',true),'')::uuid").get(0, UUID.class);
  }

  private long count(String sqlText, List<Object> params) {
    Long v = sql.fetchOne(sqlText, params.toArray()).get(0, Long.class);
    return v == null ? 0L : v;
  }

  private long count(String sqlText, Object... params) {
    return count(sqlText, List.of(params));
  }

  private static int totalPages(long total, int size) {
    return (int) Math.max(1, (total + size - 1) / size);
  }

  private static boolean blank(String s) {
    return s == null || s.isBlank();
  }

  private static String text(Object o) {
    return o == null ? "" : o.toString();
  }

  private static long longOf(Record r, String column) {
    Long v = r.get(column, Long.class);
    return v == null ? 0L : v;
  }

  private static UUID parseUuid(String id) {
    try {
      return UUID.fromString(id);
    } catch (IllegalArgumentException | NullPointerException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "id 不是合法 UUID");
    }
  }

  private static Object[] concat(List<Object> a, Object... rest) {
    Object[] out = new Object[a.size() + rest.length];
    int i = 0;
    for (Object o : a) {
      out[i++] = o;
    }
    for (Object o : rest) {
      out[i++] = o;
    }
    return out;
  }

  /** 筛选子句与绑定值。与 9.5.1.1 的 {@code Params} 同一形状。 */
  private static final class Params {
    final StringBuilder clause = new StringBuilder();
    final List<Object> params = new ArrayList<>();
  }
}
