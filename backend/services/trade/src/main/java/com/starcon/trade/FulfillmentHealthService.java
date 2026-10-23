package com.starcon.trade;

import static com.starcon.trade.FulfillmentHealthModels.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 1.1.2.3 履约健康 —— 健康度卡片 + 红灯项明细（<b>只读投影聚合</b>）。
 *
 * <p>本服务<b>不产生任何业务事实</b>：只从既有事实表读出数字，交给前端聚合成健康度卡片。
 * 卷 01 的定位（蓝图卷首）：「不产生新的业务事实，只聚合各系统的投影」「<b>不新增业务服务</b>」。
 *
 * <p><b>四张源表，全是既有事实表的只读</b>：
 *
 * <ul>
 *   <li>{@code shipment_pending_unit}（V124，9.4.1.1 待发货）—— 待发货积压；
 *   <li>{@code shipment_record}（V127，9.4.1.2 发货记录）—— 超时发货率 / 物流异常件占比 / 平台绩效；
 *   <li>{@code fulfillment_dropship_tracking_exception}（V126，9.6.1.3 代发轨迹）—— 代发路径异常；
 *   <li>{@code fulfillment_dropship_tracking}（V126）—— 代发被监测件数（分母）。
 * </ul>
 *
 * <p><b>「平均出库时长」不在这里</b>：出库时效属 08 仓储作业（8.5.3.1，warehouse 服务），
 * 它自己出数（{@code /warehouse/outbound-timeliness/report}），本模块<b>不</b>去查 warehouse 的库 ——
 * 跨服务 SQL / 跨库 FK / 共享视图三条都被 AGENTS.md 禁止。那一张卡片由前端合并时补上。
 *
 * <p><b>三处最容易做错、且做错了会误导人的地方，这里都显式处理</b>：
 *
 * <ul>
 *   <li><b>没有时限的记录既不算准时也不算迟发</b>（落 UNSET 桶）。把没时限的当准时，
 *       迟发率会系统性偏低 —— 而偏低的方向正好让人以为「一切正常」。
 *       所以每张比率型卡片都带 {@code numerator} / {@code denominator}，
 *       未登记的量在 {@link FulfillmentHealthCoverage} 里单独报出来。
 *   <li><b>迟发率与有效追踪率不重算</b>：直接调 {@link ShipmentRecordService#performance}，
 *       那个方法就是 9.4.1.2 的口径单点。同一个词两处定义，事后没人说得清哪个是真的。
 *   <li><b>量不到就是「未接通」，不是 0</b>。仓内<b>没有</b> {@code platform_performance_metric} 表，
 *       ODR（订单缺陷率）没有任何上游 ⇒ {@code available=false} 并点名归属模块，
 *       <b>不造一张表假装有</b>，也不用 0 把它填满。
 * </ul>
 *
 * <p><b>比率一律 BigDecimal</b>（4 位小数，{@code numeric(19,6)} 口径），全程禁浮点：
 * 绩效指标要拿去对账，四舍五入到整数会把「9/100 迟发」显示成「0% 迟发」。
 *
 * <p><b>租户不来自任何请求头</b>：由 {@link AccessService#run} 在事务里绑定 {@code app.current_tenant}，
 * RLS 在数据库层强制隔离；每条查询再显式写一遍 {@code tenant_id = ?} 作为第二道防线。
 */
@Service
public class FulfillmentHealthService {

  /** 业务时区（与 9.5.1.1 时效分析同一个）：刻意不跟服务器本地时区走。 */
  private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

  /** 比率保留 4 位小数。 */
  private static final int RATE_SCALE = 4;

  /** 单页最大行数。 */
  static final int MAX_PAGE_SIZE = 200;

  /**
   * 待发货时限桶。**与 9.4.1.1 的 SLA_BUCKET_SQL 逐字一致** —— 同一批货在两个页面上
   * 不能一个说「超时」一个说「今天到期」。
   */
  private static final String SLA_BUCKET_SQL =
      "case when sla_deadline_at is null then 'UNSET'"
          + " when sla_deadline_at < now() then 'OVERDUE'"
          + " when sla_deadline_at < now() + interval '24 hours' then 'DUE_TODAY'"
          + " when sla_deadline_at < now() + interval '48 hours' then 'DUE_48H'"
          + " else 'LATER' end";

  /** 待发货积压的构成状态：已打包、还没交运、也没作废。 */
  private static final String BACKLOG_STATUS_SQL =
      "status in ('PENDING','WAYBILL_REQUESTED','LABEL_PRINTED')";

  /** 迟发判据：发货时刻晚于时限。两个都是已落库的列，不碰墙上时钟。 */
  private static final String LATE_SQL =
      "(r.sla_deadline_at is not null and r.shipped_at > r.sla_deadline_at)";

  /** 红灯项类型。 */
  public static final List<String> RED_FLAG_KINDS =
      List.of(
          "PENDING_SHIPMENT_OVERDUE",
          "SHIP_OVERDUE",
          "LOGISTICS_EXCEPTION",
          "UNTRACKABLE_SHIPMENT",
          "DROPSHIP_TRACKING_EXCEPTION");

  private final DSLContext sql;
  private final ShipmentRecordService shipmentRecords;

  public FulfillmentHealthService(DSLContext sql, ShipmentRecordService shipmentRecords) {
    this.sql = sql;
    this.shipmentRecords = shipmentRecords;
  }

  // ================================================================ 能力声明

  /**
   * 能力声明：逐维度报出**量得到 / 量不到**、量不到时归谁、对本模块的影响、计算公式与分母。
   *
   * <p>口径写在服务端一处，前端不重算、不猜。
   */
  public FulfillmentHealthCapabilities capabilities() {
    List<FulfillmentHealthCapabilityDimension> dims = new ArrayList<>();

    dims.add(
        new FulfillmentHealthCapabilityDimension(
            "PENDING_SHIPMENT_BACKLOG",
            "待发货积压",
            true,
            "",
            "",
            "",
            "待发货积压 = 已打包、尚未交运、也未作废的待发货单元数（"
                + "status ∈ PENDING / WAYBILL_REQUESTED / LABEL_PRINTED）",
            "积压量本身是计数口径；红灯项（超时未发出）= 其中 sla_deadline_at < now() 的单元数，"
                + "分母同样是全部待发货单元",
            "shipment_pending_unit（V124）",
            "同服务只读聚合；本模块不写待发货单元，改状态回 9.4.1.1 待发货"));

    dims.add(
        new FulfillmentHealthCapabilityDimension(
            "AVERAGE_OUTBOUND_DURATION",
            "平均出库时长",
            true,
            "",
            "",
            "",
            "平均出库时长 = 已发货出库单的「订单到出库」时长均值（分钟）",
            "分母 = 区间内**已发货**（shipped_at 非空）的出库单数；终点未发生的单进缺失样本数，"
                + "不混进分母（夹成 0 会让「还没发」显示成「超快」）",
            "08 仓储作业 8.5.3.1 出库时效（warehouse 服务）",
            "跨服务取数：由 /warehouse/outbound-timeliness/report 出数，本模块不查 warehouse 的库；"
                + "超时判定用仓库已登记的生效阈值（仓库覆盖 > 租户默认 > 内置兜底）"));

    dims.add(
        new FulfillmentHealthCapabilityDimension(
            "SHIP_OVERDUE_RATE",
            "超时发货率",
            true,
            "",
            "",
            "",
            "超时发货率 = 发货时刻晚于发货时限的发货记录数 ÷ 登记过发货时限的发货记录数",
            "分母 = **非作废且已登记发货时限**的记录数；作废（VOID）是错发货不是迟发货，不进分母；"
                + "时限未登记的记录落 UNSET 桶，既不算准时也不算迟发",
            "shipment_record（V127）",
            "只读聚合；口径与 9.4.1.2 发货记录一致，不另立第二套算法"));

    dims.add(
        new FulfillmentHealthCapabilityDimension(
            "LOGISTICS_EXCEPTION_RATE",
            "物流异常件占比",
            true,
            "",
            "",
            "",
            "物流异常件占比 = 轨迹状态为 EXCEPTION 的发货记录数 ÷ 全部非作废发货记录数",
            "分母 = 全部非作废发货记录数（与有效追踪率同一总体，两张卡的人数一致）",
            "shipment_record（V127，tracking_status）",
            "只读聚合；轨迹来自 9.4.1.2 登记的观测，10.3 承运商轨迹回传未交付，"
                + "所以本指标只覆盖「已登记轨迹」的那部分货"));

    dims.add(
        new FulfillmentHealthCapabilityDimension(
            "PLATFORM_LATE_SHIPMENT_RATE",
            "平台迟发率",
            true,
            "",
            "",
            "",
            "平台迟发率 = 迟发记录数 ÷ 登记过时限的非作废记录数",
            "与「超时发货率」**同一分母同一分子** —— 仓内只有一份发货事实，"
                + "两个名字指的是同一个测量；不为了凑维度再造第二套算法",
            "shipment_record（V127）",
            "直接引用 9.4.1.2 的绩效口径（ShipmentRecordService.performance）"));

    dims.add(
        new FulfillmentHealthCapabilityDimension(
            "PLATFORM_VALID_TRACKING_RATE",
            "有效追踪率",
            true,
            "",
            "",
            "",
            "有效追踪率 = （有主运单号 **且** 至少有一条轨迹观测）的记录数 ÷ 全部非作废记录数",
            "分母 = 全部非作废发货记录数；两者缺一不可：只有运单号没有轨迹说明承运商没给到"
                + "（平台照样扣分），有观测但没单号则无法回传与向买家举证",
            "shipment_record + shipment_record_tracking_observation（V127）",
            "直接引用 9.4.1.2 的绩效口径，不重算"));

    dims.add(
        new FulfillmentHealthCapabilityDimension(
            "PLATFORM_ORDER_DEFECT_RATE",
            "平台 ODR（订单缺陷率）",
            false,
            "仓内**没有**任何 ODR 上游：全库找不到 platform_performance_metric 表，"
                + "也没有 A-to-z 索赔、拒付、差评率这类事实表 —— 蓝图的共享数据清单里写的"
                + "「platform_performance_metric」在本仓**不存在**。",
            "02 渠道与店铺 · 平台绩效指标同步",
            "工作台**显示不出 ODR**：健康度总览里这一格是「未接通」而不是 0；"
                + "平台绩效申诉时也拿不到这一维的举证材料（迟发率与有效追踪率不受影响）。",
            "",
            "",
            "",
            "未接通：本模块**不造一张表假装有**，也不用 0 把它填满 —— "
                + "没有来源的指标填成 0，会让人以为「平台缺陷率很好」，而它其实是「我们根本没采」"));

    return new FulfillmentHealthCapabilities(
        dims,
        RED_FLAG_KINDS,
        "灯色只由**业务事实**判定，不用拍脑袋的百分比阈值："
            + "超时未发出 / 迟发 / 轨迹异常 ⇒ 红；今日到期未发出、时限未登记、"
            + "有运单号但无轨迹观测 ⇒ 黄；其余 ⇒ 绿；量不到 ⇒ 灰（UNKNOWN）。"
            + "平台迟发阈值按平台与店铺不同，仓内无阈值配置 ⇒ 本模块只报数与迟发单量，"
            + "不判定「是否超过平台阈值」。",
        "本模块的卡片是**当前时点**的水位（待发货积压与异常件是此刻的存量），"
            + "「平均出库时长」与「超时发货率」按区间内已落库的事实计算，不依赖墙上时钟。",
        "上游 = 08 仓储作业（出库时效，warehouse 服务）/ 09 订单履约（待发货、发货记录，trade 服务）/ "
            + "10 物流与头程（代发轨迹异常，trade 服务）/ 02 渠道（平台绩效指标，**未接通**）。",
        "本服务是 shipment_pending_unit / shipment_record / fulfillment_dropship_tracking_exception "
            + "的**只读**消费方，不是写入所有者 —— 写入口分别是 9.4.1.1、9.4.1.2、9.6.1.3。",
        "本命名空间下**没有任何写端点**：工作台只读 + 跳转，一切状态变更回到归属系统完成。");
  }

  // ================================================================ 汇总

  /**
   * 健康度汇总。
   *
   * @param tenant <b>已认证成员关系解析出的租户</b>（不是客户端头，也不是可被改写的会话变量）。
   *     每条查询都拿它做显式 {@code tenant_id = ?} 谓词 —— RLS 是第一道防线，显式谓词是第二道，
   *     两者都指向同一个租户时，任何「会话租户被换掉」的伪造都只会读到<b>零行</b>，
   *     而不会退化成「读到别家租户的数据」。
   */
  @Transactional(readOnly = true)
  public FulfillmentHealthSummary summary(UUID tenant) {

    // ---- 1. 待发货积压（shipment_pending_unit）----
    Record p = sql.fetchOne(pendingSummarySql(), tenant);
    long pendingTotal = p.get("total", Long.class);
    long backlog = p.get("backlog", Long.class);
    long backlogPending = p.get("pending", Long.class);
    long backlogWaybill = p.get("waybill", Long.class);
    long backlogPrinted = p.get("printed", Long.class);
    long handedOver = p.get("handed", Long.class);
    long cancelled = p.get("cancelled", Long.class);
    long backlogOverdue = p.get("overdue", Long.class);
    long backlogDueToday = p.get("due_today", Long.class);
    long backlogUnsetSla = p.get("unset_sla", Long.class);
    long backlogParcels = p.get("backlog_parcels", Long.class);
    BigDecimal avgBacklogSeconds = p.get("avg_backlog_seconds", BigDecimal.class);

    // ---- 2. 发货记录总体与轨迹异常（shipment_record）----
    Record s =
        sql.fetchOne(
            "select count(*)::bigint as evaluated,"
                + " coalesce(sum(case when r.tracking_status = 'EXCEPTION' then 1 else 0 end),0)::bigint as exception_records,"
                + " coalesce(sum(case when r.tracking_status = 'NO_TRACE' then 1 else 0 end),0)::bigint as no_trace_records"
                + " from shipment_record r where r.tenant_id = ? and r.status <> 'VOID'",
            tenant);
    long evaluated = s.get("evaluated", Long.class);
    long exceptionRecords = s.get("exception_records", Long.class);
    long noTraceRecords = s.get("no_trace_records", Long.class);

    // ---- 3. 平台绩效口径：直接引用 9.4.1.2 的单点定义，不重算 ----
    var perf = shipmentRecords.performance(null, null, null, null, null);
    long lateRecords = perf.lateRecords();
    long onTimeRecords = perf.onTimeRecords();
    long unsetSlaRecords = perf.unsetSlaRecords();
    long slaKnown = lateRecords + onTimeRecords;
    long trackableRecords = perf.trackableRecords();
    long untrackableRecords = perf.untrackableRecords();

    // ---- 4. 代发轨迹异常（fulfillment_dropship_tracking_exception）----
    Record d =
        sql.fetchOne(
            "select (select count(*)::bigint from fulfillment_dropship_tracking t"
                + "   where t.tenant_id = ?) as trackings,"
                + " (select count(*)::bigint from fulfillment_dropship_tracking_exception e"
                + "   where e.tenant_id = ? and e.status in ('OPEN','ACKNOWLEDGED')) as open_exceptions,"
                + " (select count(distinct e.tracking_id)::bigint from fulfillment_dropship_tracking_exception e"
                + "   where e.tenant_id = ? and e.status in ('OPEN','ACKNOWLEDGED')) as open_exception_trackings",
            tenant,
            tenant,
            tenant);
    long dropshipTrackings = d.get("trackings", Long.class);
    long dropshipOpenExceptions = d.get("open_exceptions", Long.class);

    List<FulfillmentHealthCard> cards = new ArrayList<>();

    // ---- 卡片 1：待发货积压 ----
    cards.add(
        new FulfillmentHealthCard(
            "PENDING_SHIPMENT_BACKLOG",
            "待发货积压",
            true,
            "",
            "",
            "COUNT",
            "件",
            BigDecimal.valueOf(backlog),
            backlog + " 件",
            null,
            null,
            backlogOverdue,
            "PENDING_SHIPMENT_OVERDUE",
            "积压 = 已打包未交运未作废的待发货单元（这是**计数**，不是比率，所以没有分子分母）；"
                + "其中 " + backlogOverdue + " 件已过发货时限（红灯），"
                + backlogDueToday + " 件今日到期，涉及包裹 " + backlogParcels + " 件，"
                + "平均已积压 " + hoursText(avgBacklogSeconds),
            levelOf(
                pendingTotal == 0,
                backlogOverdue > 0,
                backlogDueToday > 0 || backlogUnsetSla > 0),
            "红灯 = 存在已过发货时限仍未发出的单元；黄灯 = 今日到期未发出，或存在时限未登记的单元"
                + "（时限没登记 ⇒ 这件货还没进迟发率的分母）；投影整体为空时判灰（还没有任何待发货投影）",
            "/orders/fulfillment/pending-shipment",
            List.of("shipment_pending_unit")));

    // ---- 卡片 2：超时发货率 ----
    cards.add(
        new FulfillmentHealthCard(
            "SHIP_OVERDUE_RATE",
            "超时发货率",
            true,
            "",
            "",
            "RATE",
            "%",
            slaKnown > 0 ? rate(lateRecords, slaKnown) : null,
            slaKnown > 0 ? rateText(lateRecords, slaKnown) : "未接通",
            lateRecords,
            slaKnown,
            lateRecords,
            "SHIP_OVERDUE",
            "迟发 = 发货时刻晚于发货时限；分母 = 登记过时限的非作废记录 " + slaKnown + " 条，"
                + "其中迟发 " + lateRecords + " 条、准时 " + onTimeRecords + " 条；"
                + "另有 " + unsetSlaRecords + " 条时限未登记（落 UNSET 桶，不进分母）",
            levelOf(evaluated == 0, lateRecords > 0, lateRecords == 0 && unsetSlaRecords > 0),
            "红灯 = 有迟发记录；黄灯 = 没有迟发但有记录未登记时限（迟发率可能偏低）；"
                + "没有任何已发货记录时判灰",
            "/orders/fulfillment/shipment-records",
            List.of("shipment_record")));

    // ---- 卡片 3：物流异常件占比 ----
    cards.add(
        new FulfillmentHealthCard(
            "LOGISTICS_EXCEPTION_RATE",
            "物流异常件占比",
            true,
            "",
            "",
            "RATE",
            "%",
            evaluated > 0 ? rate(exceptionRecords, evaluated) : null,
            evaluated > 0 ? rateText(exceptionRecords, evaluated) : "未接通",
            exceptionRecords,
            evaluated,
            exceptionRecords,
            "LOGISTICS_EXCEPTION",
            "轨迹状态为 EXCEPTION 的发货记录 " + exceptionRecords + " 件，占非作废发货记录 "
                + evaluated + " 件；另有 " + noTraceRecords + " 件轨迹状态为 NO_TRACE（承运商没给到轨迹）",
            levelOf(evaluated == 0, exceptionRecords > 0, false),
            "红灯 = 存在轨迹异常记录；轨迹状态 NO_TRACE 计入有效追踪率那一维的告警，"
                + "不重复计入本卡的分子（同一件货不重复报警）",
            "/orders/fulfillment/shipment-records",
            List.of("shipment_record")));

    // ---- 卡片 4：平台迟发率（与超时发货率同一测量，两个名字）----
    cards.add(
        new FulfillmentHealthCard(
            "PLATFORM_LATE_SHIPMENT_RATE",
            "平台迟发率",
            true,
            "",
            "",
            "RATE",
            "%",
            slaKnown > 0 ? rate(lateRecords, slaKnown) : null,
            slaKnown > 0 ? rateText(lateRecords, slaKnown) : "未接通",
            lateRecords,
            slaKnown,
            lateRecords,
            "SHIP_OVERDUE",
            "与「超时发货率」是**同一个测量**：仓内只有一份发货事实（shipment_record），"
                + "两个名字分别对应履约链路视角与平台绩效视角。分母同上 = " + slaKnown + " 条。",
            levelOf(evaluated == 0, lateRecords > 0, lateRecords == 0 && unsetSlaRecords > 0),
            "与「超时发货率」同一判定；平台迟发阈值按平台与店铺不同，仓内无阈值配置 ⇒ "
                + "只报数与迟发单量，不判定「是否超过平台阈值」",
            "/orders/fulfillment/shipment-records",
            List.of("shipment_record")));

    // ---- 卡片 5：有效追踪率 ----
    cards.add(
        new FulfillmentHealthCard(
            "PLATFORM_VALID_TRACKING_RATE",
            "有效追踪率",
            true,
            "",
            "",
            "RATE",
            "%",
            evaluated > 0 ? rate(trackableRecords, evaluated) : null,
            evaluated > 0 ? rateText(trackableRecords, evaluated) : "未接通",
            trackableRecords,
            evaluated,
            untrackableRecords,
            "UNTRACKABLE_SHIPMENT",
            "可追踪 = 有主运单号且至少有一条轨迹观测：" + trackableRecords + " / " + evaluated
                + "；不可追踪 " + untrackableRecords + " 件（两者缺一不可：只有运单号没有轨迹，"
                + "说明承运商没给到轨迹，平台照样扣分）",
            levelOf(evaluated == 0, false, untrackableRecords > 0),
            "黄灯 = 存在「有主运单号但无任何轨迹观测」的记录；没有已发货记录时判灰。"
                + "**不设百分比阈值** —— 平台的有效追踪率门槛按平台与店铺不同，仓内无阈值配置，"
                + "拍一个数会把「低于平台门槛」与「高于平台门槛」混成同一个结论",
            "/orders/fulfillment/shipment-records",
            List.of("shipment_record", "shipment_record_tracking_observation")));

    // ---- 卡片 6：平台 ODR —— 未接通 ----
    cards.add(
        new FulfillmentHealthCard(
            "PLATFORM_ORDER_DEFECT_RATE",
            "平台 ODR（订单缺陷率）",
            false,
            "仓内没有任何 ODR 上游：没有 platform_performance_metric 表，"
                + "也没有 A-to-z 索赔 / 拒付 / 差评率这类事实表。",
            "02 渠道与店铺 · 平台绩效指标同步",
            "UNAVAILABLE",
            "%",
            null,
            "未接通",
            null,
            null,
            null,
            null,
            "这一格是「未接通」而不是 0：把没有来源的指标填成 0，"
                + "会让人以为「平台缺陷率很好」，而它其实是「我们根本没采」。",
            "UNKNOWN",
            "available=false ⇒ 固定判灰，不参与任何红黄绿判定，也不进合计",
            "",
            List.of()));

    // ---- 拆分 ----
    List<FulfillmentHealthBreakdown> breakdowns = new ArrayList<>();
    breakdowns.add(
        new FulfillmentHealthBreakdown(
            "PENDING_BY_STATUS",
            "待发货积压 · 按状态",
            List.of(
                new FulfillmentHealthBucket("PENDING", "待交接", backlogPending),
                new FulfillmentHealthBucket("WAYBILL_REQUESTED", "已申请运单", backlogWaybill),
                new FulfillmentHealthBucket("LABEL_PRINTED", "已打印面单", backlogPrinted))));
    breakdowns.add(
        new FulfillmentHealthBreakdown(
            "PENDING_BY_SLA",
            "待发货积压 · 按时限",
            List.of(
                new FulfillmentHealthBucket("OVERDUE", "已超期", backlogOverdue),
                new FulfillmentHealthBucket("DUE_TODAY", "今日到期", backlogDueToday),
                new FulfillmentHealthBucket("UNSET", "时限未登记", backlogUnsetSla))));
    breakdowns.add(
        new FulfillmentHealthBreakdown(
            "SHIPMENT_BY_TRACKING",
            "已发货件 · 按轨迹状态",
            trackingBreakdown(tenant, evaluated)));
    breakdowns.add(
        new FulfillmentHealthBreakdown(
            "DROPSHIP_EXCEPTION_BY_KIND",
            "代发路径未结案异常 · 按类型",
            dropshipExceptionBreakdown(tenant)));

    FulfillmentHealthCoverage coverage =
        new FulfillmentHealthCoverage(
            pendingTotal,
            handedOver,
            cancelled,
            evaluated,
            unsetSlaRecords,
            untrackableRecords,
            dropshipOpenExceptions,
            dropshipTrackings);

    List<String> notes = new ArrayList<>();
    notes.add("所有比率都带分母：分母为 0 时显示「未接通」而不是 0%");
    notes.add("作废（VOID）发货记录不计入任何分母：撤销的是错发货，不是迟发货");
    notes.add("「平均出库时长」不在本响应里：出库时效由 08 仓储作业的服务自己出数"
        + "（/warehouse/outbound-timeliness/report），前端合并时补上那张卡");
    notes.add("代发路径（fulfillment_dropship_*）只覆盖代发订单，"
        + "自有履约路径的轨迹异常走「物流异常件占比」那张卡 —— 两个总体不同，不合并");
    notes.add("平台 ODR 未接通：仓内没有该上游，显示为灰格，不参与任何判定");

    return new FulfillmentHealthSummary(cards, breakdowns, coverage, notes);
  }

  /** 待发货汇总。**每一列都 coalesce 到 0**：零行时 sum() 是 NULL，直接塞 long 会 NPE。 */
  private String pendingSummarySql() {
    return "select count(*)::bigint as total,"
        + " coalesce(sum(case when " + BACKLOG_STATUS_SQL + " then 1 else 0 end),0)::bigint as backlog,"
        + " coalesce(sum(case when status = 'PENDING' then 1 else 0 end),0)::bigint as pending,"
        + " coalesce(sum(case when status = 'WAYBILL_REQUESTED' then 1 else 0 end),0)::bigint as waybill,"
        + " coalesce(sum(case when status = 'LABEL_PRINTED' then 1 else 0 end),0)::bigint as printed,"
        + " coalesce(sum(case when status = 'HANDED_OVER' then 1 else 0 end),0)::bigint as handed,"
        + " coalesce(sum(case when status = 'CANCELLED' then 1 else 0 end),0)::bigint as cancelled,"
        + " coalesce(sum(case when " + BACKLOG_STATUS_SQL + " and " + SLA_BUCKET_SQL
        + " = 'OVERDUE' then 1 else 0 end),0)::bigint as overdue,"
        + " coalesce(sum(case when " + BACKLOG_STATUS_SQL + " and " + SLA_BUCKET_SQL
        + " = 'DUE_TODAY' then 1 else 0 end),0)::bigint as due_today,"
        + " coalesce(sum(case when " + BACKLOG_STATUS_SQL + " and " + SLA_BUCKET_SQL
        + " = 'UNSET' then 1 else 0 end),0)::bigint as unset_sla,"
        + " coalesce(sum(case when " + BACKLOG_STATUS_SQL
        + " then parcel_count else 0 end),0)::bigint as backlog_parcels,"
        + " round(avg(extract(epoch from (now() - packed_at)))"
        + "   filter (where " + BACKLOG_STATUS_SQL + ")::numeric, 0) as avg_backlog_seconds"
        + " from shipment_pending_unit u where u.tenant_id = ?";
  }

  /** 已发货件按轨迹状态拆分（含「无记录」这一格，免得空看板看起来像全 0）。 */
  private List<FulfillmentHealthBucket> trackingBreakdown(UUID tenant, long evaluated) {
    List<FulfillmentHealthBucket> out = new ArrayList<>();
    Map<String, Long> counts = new LinkedHashMap<>();
    for (Record r :
        sql.fetch(
            "select r.tracking_status as k, count(*)::bigint as c"
                + " from shipment_record r where r.tenant_id = ? and r.status <> 'VOID'"
                + " group by r.tracking_status order by 2 desc",
            tenant)) {
      counts.put(r.get("k", String.class), r.get("c", Long.class));
    }
    out.add(new FulfillmentHealthBucket("EXCEPTION", "轨迹异常", counts.getOrDefault("EXCEPTION", 0L)));
    out.add(new FulfillmentHealthBucket("NO_TRACE", "无轨迹", counts.getOrDefault("NO_TRACE", 0L)));
    out.add(new FulfillmentHealthBucket("DELIVERED", "已送达", counts.getOrDefault("DELIVERED", 0L)));
    out.add(new FulfillmentHealthBucket("IN_TRANSIT", "在途", counts.getOrDefault("IN_TRANSIT", 0L)));
    long known =
        out.stream().mapToLong(FulfillmentHealthBucket::count).sum();
    out.add(new FulfillmentHealthBucket("OTHER", "其它状态", Math.max(0, evaluated - known)));
    return out;
  }

  /** 代发路径未结案异常按类型拆分。 */
  private List<FulfillmentHealthBucket> dropshipExceptionBreakdown(UUID tenant) {
    List<FulfillmentHealthBucket> out = new ArrayList<>();
    Map<String, Long> counts = new LinkedHashMap<>();
    for (Record r :
        sql.fetch(
            "select e.exception_kind as k, count(*)::bigint as c"
                + " from fulfillment_dropship_tracking_exception e"
                + " where e.tenant_id = ? and e.status in ('OPEN','ACKNOWLEDGED')"
                + " group by e.exception_kind order by 2 desc",
            tenant)) {
      counts.put(r.get("k", String.class), r.get("c", Long.class));
    }
    out.add(new FulfillmentHealthBucket("DELAYED_SHIPMENT", "延迟发货",
        counts.getOrDefault("DELAYED_SHIPMENT", 0L)));
    out.add(new FulfillmentHealthBucket("PARCEL_LOST", "包裹丢失",
        counts.getOrDefault("PARCEL_LOST", 0L)));
    out.add(new FulfillmentHealthBucket("SUPPLIER_RUNNING_AWAY", "供应商跑路",
        counts.getOrDefault("SUPPLIER_RUNNING_AWAY", 0L)));
    return out;
  }

  // ================================================================ 红灯项明细

  /**
   * 红灯项明细（真分页）。
   *
   * <p><b>口径与卡片完全一致</b>：同一个 WHERE 子句，同一个总体。红灯项的 {@code total}
   * 与卡片上的分子一致 —— 这是「点进去看到的明细」与「卡片上的数字」不会打架的保证。
   */
  @Transactional(readOnly = true)
  public FulfillmentHealthRedFlagPage redFlags(UUID tenant, String kind, int page, int size) {
    int limit = pageSize(size);
    int offset = Math.max(0, page) * limit;

    String kindFilter = kind == null || kind.isBlank() ? "" : " where t.kind = ?";
    List<Object> countParams = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      countParams.add(tenant);
    }
    if (!kindFilter.isEmpty()) {
      countParams.add(kind.trim());
    }

    long total =
        sql.fetchOne("select count(*)::bigint from (" + unionSql() + ") t" + kindFilter,
                countParams.toArray())
            .get(0, Long.class);

    List<Object> pageParams = new ArrayList<>(countParams);
    pageParams.add(limit);
    pageParams.add(offset);

    List<FulfillmentHealthRedFlag> items = new ArrayList<>();
    for (Record r :
        sql.fetch(
            "select * from (" + unionSql() + ") t" + kindFilter
                + " order by t.occurred_at desc nulls last, t.kind, t.ref_id"
                + " limit ? offset ?",
            pageParams.toArray())) {
      items.add(
          new FulfillmentHealthRedFlag(
              r.get("kind", String.class),
              redFlagKindLabel(r.get("kind", String.class)),
              r.get("ref_id", UUID.class),
              r.get("ref_no", String.class),
              r.get("title", String.class),
              r.get("detail", String.class),
              r.get("severity", String.class),
              r.get("status", String.class),
              r.get("occurred_at", java.time.OffsetDateTime.class),
              r.get("link_path", String.class)));
    }

    List<String> notes = new ArrayList<>();
    notes.add("红灯项的判定与健康度卡片**同源同口径**：本页 total 等于对应卡片的分子");
    notes.add("待发货超时的判定用 9.4.1.1 登记的发货时限；时限未登记的单元**不会**出现在这里"
        + "（它不是「准时」，只是「没时限」）");
    notes.add("代发路径异常只覆盖代发订单；自有履约路径的轨迹异常走「物流异常件占比」那张卡");

    return new FulfillmentHealthRedFlagPage(items, total, Math.max(0, page), limit, notes);
  }

  /**
   * 四类红灯项的 UNION ALL。
   *
   * <p>刻意用 UNION ALL 而不是四次查询后 Java 侧合并：合并后再分页的话，
   * 「总数」与「本页 20 条」的分页边界会随排序漂移 —— 点进去看到的条数与卡片对不上。
   */
  private String unionSql() {
    String ts = "to_char(%s at time zone 'Asia/Shanghai', 'YYYY-MM-DD HH24:MI')";
    return "select 'PENDING_SHIPMENT_OVERDUE'::text as kind,"
        + " u.id as ref_id,"
        + " u.outbound_order_no::text as ref_no,"
        + " '待发货超时未发出'::text as title,"
        + " ('仓库 ' || u.warehouse_ref"
        + " || ' · 承运渠道 ' || u.carrier_channel"
        + " || ' · 打包于 ' || "
        + String.format(ts, "u.packed_at")
        + " || ' · 发货时限 ' || "
        + String.format(ts, "u.sla_deadline_at")
        + " || ' · 已超期 ' || floor(extract(epoch from (now() - u.sla_deadline_at)) / 3600)::text"
        + " || ' 小时')::text as detail,"
        + " 'CRITICAL'::text as severity,"
        + " u.status::text as status,"
        + " u.sla_deadline_at as occurred_at,"
        + " '/orders/fulfillment/pending-shipment'::text as link_path"
        + " from shipment_pending_unit u"
        + " where u.tenant_id = ? and u.status in ('PENDING','WAYBILL_REQUESTED','LABEL_PRINTED')"
        + " and u.sla_deadline_at is not null and u.sla_deadline_at < now()"
        + " union all"
        + " select 'SHIP_OVERDUE'::text, r.id, r.record_no::text, '迟发记录'::text,"
        + " ('发货于 ' || "
        + String.format(ts, "r.shipped_at")
        + " || ' · 发货时限 ' || "
        + String.format(ts, "r.sla_deadline_at")
        + " || ' · 迟发 ' || floor(extract(epoch from (r.shipped_at - r.sla_deadline_at)) / 3600)::text"
        + " || ' 小时 · 承运商 ' || r.carrier_name"
        + " || ' · 销售订单 ' || r.sales_order_no)::text,"
        + " 'CRITICAL'::text, r.status::text, r.shipped_at,"
        + " '/orders/fulfillment/shipment-records'::text"
        + " from shipment_record r"
        + " where r.tenant_id = ? and r.status <> 'VOID'"
        + " and r.sla_deadline_at is not null and r.shipped_at > r.sla_deadline_at"
        + " union all"
        + " select 'LOGISTICS_EXCEPTION'::text, r.id, r.record_no::text, '轨迹异常'::text,"
        + " ('轨迹状态 ' || r.tracking_status"
        + " || ' · 承运商 ' || r.carrier_name"
        + " || ' · 主运单号 ' || r.master_waybill_no"
        + " || ' · 销售订单 ' || r.sales_order_no)::text,"
        + " 'WARNING'::text, r.tracking_status::text, r.tracking_updated_at,"
        + " '/orders/fulfillment/shipment-records'::text"
        + " from shipment_record r"
        + " where r.tenant_id = ? and r.status <> 'VOID' and r.tracking_status = 'EXCEPTION'"
        + " union all"
        + " select 'UNTRACKABLE_SHIPMENT'::text, r.id, r.record_no::text, '有运单号但无轨迹观测'::text,"
        + " ('主运单号 ' || r.master_waybill_no"
        + " || ' · 承运商 ' || r.carrier_name"
        + " || ' · 销售订单 ' || r.sales_order_no)::text,"
        + " 'WARNING'::text, r.tracking_status::text, r.shipped_at,"
        + " '/orders/fulfillment/shipment-records'::text"
        + " from shipment_record r"
        + " where r.tenant_id = ? and r.status <> 'VOID'"
        + " and btrim(r.master_waybill_no) <> ''"
        + " and not exists (select 1 from shipment_record_tracking_observation o"
        + "                  where o.tenant_id = r.tenant_id and o.record_id = r.id)"
        + " union all"
        + " select 'DROPSHIP_TRACKING_EXCEPTION'::text, e.id, e.exception_no::text,"
        + " '代发轨迹异常'::text,"
        + " ('异常类型 ' || e.exception_kind"
        + " || ' · 严重度 ' || e.severity"
        + " || ' · 供应商 ' || e.supplier_code"
        + " || ' · 销售订单 ' || e.sales_order_no"
        + " || ' · 判定规则 ' || e.rule_code)::text,"
        + " e.severity::text, e.status::text, e.detected_at,"
        + " '/orders/fulfillment/dropship-tracking'::text"
        + " from fulfillment_dropship_tracking_exception e"
        + " where e.tenant_id = ? and e.status in ('OPEN','ACKNOWLEDGED')";
  }

  private static String redFlagKindLabel(String kind) {
    return switch (kind) {
      case "PENDING_SHIPMENT_OVERDUE" -> "待发货超时未发出";
      case "SHIP_OVERDUE" -> "迟发记录";
      case "LOGISTICS_EXCEPTION" -> "轨迹异常";
      case "UNTRACKABLE_SHIPMENT" -> "有运单号但无轨迹观测";
      case "DROPSHIP_TRACKING_EXCEPTION" -> "代发轨迹异常";
      default -> kind;
    };
  }

  // ================================================================ 工具

  private static int pageSize(int size) {
    if (size <= 0) return 20;
    return Math.min(size, MAX_PAGE_SIZE);
  }

  /** 比率（0..1）。分母为 0 时返回 null —— 调用方据此显示「未接通」，**不是 0**。 */
  private static BigDecimal rate(long numerator, long denominator) {
    if (denominator <= 0) return null;
    return BigDecimal.valueOf(numerator)
        .divide(BigDecimal.valueOf(denominator), RATE_SCALE, RoundingMode.HALF_UP);
  }

  /** 比率展示串：百分比，4 位小数。 */
  private static String rateText(long numerator, long denominator) {
    BigDecimal r = rate(numerator, denominator);
    if (r == null) return "未接通";
    return r.movePointRight(2).setScale(2, RoundingMode.HALF_UP) + "%";
  }

  /**
   * 灯色。
   *
   * @param unknown 量不到（没有样本）⇒ 灰
   * @param red 命中红灯条件
   * @param amber 命中黄灯条件
   */
  private static String levelOf(boolean unknown, boolean red, boolean amber) {
    if (unknown) return "UNKNOWN";
    if (red) return "RED";
    if (amber) return "AMBER";
    return "GREEN";
  }

  /** 平均积压秒数的展示串（小时）。null 时如实说「无样本」。 */
  private static String hoursText(BigDecimal seconds) {
    if (seconds == null) return "无样本";
    BigDecimal hours =
        seconds.divide(BigDecimal.valueOf(3600), 2, RoundingMode.HALF_UP);
    return hours.stripTrailingZeros().toPlainString() + " 小时";
  }
}
