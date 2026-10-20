package com.starcon.channel;

import static com.starcon.channel.TrackingQueryModels.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.channel.carrier.CarrierTrackingAdapter;
import com.starcon.channel.carrier.CarrierTrackingAdapterRegistry;
import com.starcon.tenancy.Ids;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 10.3.1.1 轨迹查询。
 *
 * <p>蓝图：「查询并展示包裹物流轨迹：节点时间线（揽收-运输-清关-转运-派送-签收）、
 * 预计时效对比、异常节点标记，支持按承运商批量拉取。」输出「轨迹时间线 +
 * 预计到达时间 + 异常标记」，并加粗
 * 「<b>按承运商事件 ID 去重</b>，同一节点重复推送不产生重复记录」。
 *
 * <p>🔴 本类唯一不能妥协的约束是那条加粗句。它的落地是三件事同时做，缺一件就退化成
 * 「把每次推送都记下来」那种假活：
 *
 * <ol>
 *   <li><b>去重键在 DB 上</b>：{@code UNIQUE (tenant_id, carrier_code, carrier_event_id)}
 *       （V212）。服务层的 {@code on conflict do nothing} 只是<b>友好</b>，
 *       真正的闸门是<b>绕过服务层直接写 SQL 插同一条事件同样撞 23505</b> ——
 *       测试用应用账号与迁移超级账号<b>各试一次</b>。
 *   <li><b>去重键带 carrier_code</b>：不同承运商的事件号空间互相独立，
 *       同一个 {@code EVT-1} 在 A 家与 B 家是两条不同的事件。
 *   <li><b>事件 append-only</b>：{@code REVOKE UPDATE,DELETE} + 触发器双保险。
 *       轨迹是「当时它在哪」的举证，事后改写等于伪造物流史。
 * </ol>
 *
 * <p>数据来源纪律：
 * <ul>
 *   <li><b>运单 / 发货记录</b>在 <b>trade</b>（9.4.1.2），本服务只经
 *       {@link ShipmentTrackingQueryPort} 走<b>授权 HTTP 只读接口</b>读，
 *       <b>转发调用方自己的 bearer</b>（租户隔离是 channel 侧 RLS + trade 侧 RLS
 *       <b>两道</b>）。本类<b>一条 SQL 都不碰 trade / warehouse 的库</b>。</li>
 *   <li><b>时效承诺</b>在<b>同库</b>只读：{@code logistics_transit_rule}（V139，10.1.1.3）。
 *       承诺按<b>发货当时</b>的口径算成快照存下来 —— 事后改档期不能让一笔
 *       已经延误的货变成准点。</li>
 *   <li><b>轨迹事件</b>是<b>本服务自持</b>的账本：承运商推送进来的、按承运商批量
 *       拉取进来的，都落这里。</li>
 * </ul>
 *
 * <p>异常节点的边界：<b>只标记，不处置</b>。蓝图 10.3.1.2（异常件，<b>未交付</b>）的
 * 输入句里有「轨迹异常规则」，而 10.3.1.1 的职责句是「异常<b>节点标记</b>」——
 * 所以本模块标「这一票有异常节点、异常码是什么」，
 * 而<b>不</b>提供联系承运商 / 理赔 / 补发 / 改址这类处置入口（那会造出第二份处置台账）。
 */
@Service
public class TrackingQueryService {

  // ================================================================
  // 常量：枚举取值与标签
  // ================================================================

  /** 节点类型（V212 的 CHECK 就是这九档）。 */
  static final List<String> NODE_TYPES =
      List.of(
          "NO_TRACE", "PICKED_UP", "IN_TRANSIT", "CUSTOMS", "TRANSFER",
          "OUT_FOR_DELIVERY", "DELIVERED", "EXCEPTION", "RETURNED");

  /** 时效状态（读时算出，不是存下来的）。 */
  static final List<String> ETA_STATUSES =
      List.of("ON_TIME", "AT_RISK", "DELAYED", "NO_PROMISE", "NO_ETA");

  static final List<String> SUBJECT_STATUSES =
      List.of("ACTIVE", "DELIVERED", "RETURNED", "VOID");

  static final List<String> EVENT_SOURCES =
      List.of("CARRIER_PUSH", "CARRIER_PULL", "MANUAL_ATTESTATION");

  static final List<String> PULL_TASK_STATUSES =
      List.of("PENDING", "RUNNING", "SUCCEEDED", "PARTIAL", "FAILED", "NO_CHANNEL");

  static final List<String> PULL_ITEM_STATUSES =
      List.of("PULLED", "DUPLICATE", "NO_EVENT", "FAILED", "SKIPPED");

  static final List<String> SERVICE_LEVELS = List.of("ECONOMY", "STANDARD", "EXPRESS");

  /**
   * 时效状态表达式。<b>读时</b>算出来，不存成静态结论 ——
   * 「是否延误」取决于现在几点：上午十点还在承诺时效内、下午六点就超了。
   *
   * <p>判据（自上而下短路）：
   * <ol>
   *   <li>没有时效承诺 ⇒ {@code NO_PROMISE}（连承诺都没有，谈不上延误）；</li>
   *   <li>已签收：签收时刻 ≤ 承诺最晚到达 ⇒ {@code ON_TIME}，否则 {@code DELAYED}；</li>
   *   <li>承运商给了预计：≤ 承诺最晚到达 ⇒ {@code ON_TIME}，否则 {@code AT_RISK}
   *       （承运商自己说会晚，但还没超期）；</li>
   *   <li>承运商没给预计，但承诺时刻已经过去 ⇒ {@code DELAYED}；</li>
   *   <li>其余 ⇒ {@code NO_ETA}（承运商还没给预计，承诺也还没到）。</li>
   * </ol>
   */
  static final String ETA_STATUS_EXPR =
      "case"
          + " when s.promised_eta_at is null then 'NO_PROMISE'"
          + " when s.status = 'DELIVERED' then (case when s.latest_occurred_at is not null"
          + "      and s.latest_occurred_at <= s.promised_eta_at then 'ON_TIME' else 'DELAYED' end)"
          + " when s.carrier_eta_at is not null and s.carrier_eta_at <= s.promised_eta_at then 'ON_TIME'"
          + " when s.carrier_eta_at is not null then 'AT_RISK'"
          + " when now() > s.promised_eta_at then 'DELAYED'"
          + " else 'NO_ETA' end";

  // ================================================================
  // 依赖
  // ================================================================

  private final DSLContext sql;
  private final ObjectMapper json;
  private final ShipmentTrackingQueryPort shipments;
  private final CarrierTrackingAdapterRegistry adapters;

  public TrackingQueryService(
      DSLContext sql,
      ObjectMapper json,
      ShipmentTrackingQueryPort shipments,
      CarrierTrackingAdapterRegistry adapters) {
    this.sql = sql;
    this.json = json;
    this.shipments = shipments;
    this.adapters = adapters;
  }

  private static String text(String value) {
    return value == null ? "" : value.trim();
  }

  private static String escapeLike(String raw) {
    return raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }

  /** 当前事务内的租户（由 backend/libraries/tenancy 在进入事务时绑定）。 */
  private UUID currentTenant() {
    Record row = sql.fetchOne("select nullif(current_setting('app.current_tenant',true),'')");
    String raw = row == null ? null : row.get(0, String.class);
    if (raw == null || raw.isBlank()) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "缺少租户上下文");
    }
    return UUID.fromString(raw);
  }

  /** 跨租户与不存在返回<b>同一个 404 且理由逐字相同</b>（照抄 10.1.1.2 / 10.1.1.3 同款口径）。 */
  private static ResponseStatusException notFound() {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, "轨迹主体不存在");
  }

  // ==================================================================
  // 能力声明
  // ==================================================================

  public TrackingCapabilities capabilities() {
    List<TrackingCapabilityDimension> dimensions =
        List.of(
            new TrackingCapabilityDimension(
                "tracking_timeline",
                "🔴 轨迹节点时间线（揽收-运输-清关-转运-派送-签收）",
                true,
                "本服务 V212 logistics_tracking_event（append-only，承运商事件 ID 去重）",
                "",
                "同一节点重复推送不会产生第二条记录：去重落在 DB 唯一约束上，"
                    + "绕过服务层直接写 SQL 同样撞 23505"),
            new TrackingCapabilityDimension(
                "shipment_subject",
                "运单 / 包裹集合（轨迹主体的来源）",
                true,
                "上游 9.4.1.2 trade shipment_record，经 ShipmentTrackingQueryPort 走"
                    + " GET /trade/fulfillment/shipment-records/records（转发调用方 bearer）",
                "",
                "运单真值在 trade，本服务只持有一份投影；租户隔离是 channel 侧 RLS + "
                    + "trade 侧 RLS 两道，上游读不到时 503 而不是空清单"),
            new TrackingCapabilityDimension(
                "transit_promise",
                "🔴 预计时效对比（我方承诺 vs 承运商预计）",
                true,
                "同库只读 10.1.1.3 logistics_transit_rule（V139）+ 承运商事件里的预计到达",
                "",
                "承诺按发货当时的档期算成快照；事后改档期不会让已经延误的货变成准点"),
            new TrackingCapabilityDimension(
                "exception_marking",
                "🔴 异常节点标记（只标记，不处置）",
                true,
                "本服务 V212 logistics_tracking_event.exception_code + 主体级异常聚合",
                "",
                "标了异常就必须说清是什么异常（表级 CHECK）；处置（联系承运商 / 理赔 / "
                    + "补发 / 改址）不在本页"),
            new TrackingCapabilityDimension(
                "exception_rules",
                "轨迹异常规则（轨迹停滞、清关滞留、妥投失败等**主动识别**规则）",
                false,
                "10.3.1.2 异常件的输入句点名的规则集，未交付",
                "10.3.1.2",
                "本模块只标「承运商报了这个节点是异常」；"
                    + "「多久没动算停滞」这类主动规则要等 10.3.1.2"),
            new TrackingCapabilityDimension(
                "exception_disposition",
                "异常件处置（联系承运商 / 理赔 / 补发 / 改址 / 责任人）",
                false,
                "10.3.1.2 异常件，未交付",
                "10.3.1.2",
                "本页**不提供任何处置入口** —— 造出第二份处置台账会让责任无处可查"),
            new TrackingCapabilityDimension(
                "carrier_push",
                "承运商推送轨迹（webhook 落账）",
                true,
                "本服务 POST /channel/logistics-tracking/events",
                "",
                "事件真落 PostgreSQL 16；重复推送按事件 ID 去重，不产生重复记录"),
            new TrackingCapabilityDimension(
                "carrier_pull",
                "按承运商批量拉取（轮询任务）",
                true,
                "本服务 POST /channel/logistics-tracking/pull-tasks + "
                    + "CarrierTrackingAdapter 端口",
                "",
                "拉取入口与任务账本已交付；当前**没有已接通的 vendor 实现**，"
                    + "那一轮会如实落 NO_CHANNEL 并写明理由，**不返回空轨迹冒充「没轨迹」**"),
            new TrackingCapabilityDimension(
                "carrier_adapter",
                "承运商轨迹通道（出网连接与凭据）",
                false,
                "10.1.1.1 承运商列表交付的是档案与规则，不是出网连接",
                "10.1.1.1",
                "没有通道 ⇒ 批量拉取落 NO_CHANNEL；承运商**推送**那条路仍然可用"),
            new TrackingCapabilityDimension(
                "polling_scheduler",
                "轮询调度器（自带定时器）",
                false,
                "本模块**只提供拉取入口**，调度由外部触发（K8s CronJob / 平台任务执行器）",
                "外部调度",
                "多副本下自带定时器要做主节点选举与分布式锁，那是平台能力不是领域能力；"
                    + "外部触发一次入口反而天然单次、可重试、可审计"),
            new TrackingCapabilityDimension(
                "pickup_delivery_confirmation",
                "揽收 / 签收确认与提醒任务",
                false,
                "10.3.2.2 揽收与签收确认，未交付",
                "10.3.2.2",
                "本模块的 DELIVERED 节点是**承运商报的事实**，"
                    + "「主动确认 + 触发评价邀请」要等 10.3.2.2"),
            new TrackingCapabilityDimension(
                "per_parcel_tracking",
                "逐包裹（逐箱）轨迹",
                false,
                "跨境场景下轨迹以主运单号（master waybill）为键；逐箱轨迹需要承运商逐箱报",
                "10.3.1.2",
                "一票多箱时本页展示的是**整票**轨迹；逐箱差异要等逐箱轨迹能力"),
            new TrackingCapabilityDimension(
                "freight_cost_settlement",
                "运费成本归集与结算",
                false,
                "12 财务未交付；本模块也不产生任何金额",
                "12",
                "本页只处理时间与状态，**不涉及任何金额** —— 成本口径归 12，"
                    + "本页自造一个金额列会造出第二套成本口径"));
    return new TrackingCapabilities(
        dimensions,
        NODE_TYPES,
        ETA_STATUSES,
        SUBJECT_STATUSES,
        EVENT_SOURCES,
        PULL_TASK_STATUSES,
        PULL_ITEM_STATUSES,
        SERVICE_LEVELS,
        "运单真值在 trade（9.4.1.2），本服务只经授权 HTTP 只读接口读，"
            + "转发调用方自己的 bearer；轨迹事件是本服务自持的账本，"
            + "按承运商事件 ID 在 DB 层去重；异常只标记不处置（处置归 10.3.1.2）。",
        "🔴 承运商事件 ID 去重落在 DB 唯一约束上 —— 同一节点重复推送不产生重复记录，"
            + "绕过服务层直接写 SQL 同样被拒；"
            + "异常节点**只标记不处置**，处置入口归 10.3.1.2；"
            + "本页**不涉及任何金额**（成本归 12 财务）。");
  }

  // ==================================================================
  // 读：轨迹清单（真分页 + 真筛选 + 不受分页影响的真聚合）
  // ==================================================================

  private static final String SUBJECT_COLUMNS =
      "s.id,s.source_shipment_record_id,s.source_record_no,s.sales_order_no,s.outbound_order_no,"
          + "s.warehouse_ref,s.carrier_code,s.master_waybill_no,s.destination_country,"
          + "s.channel_id,c.channel_code,c.channel_name,"
          + "s.gross_weight_kg,s.service_level,s.parcel_count,s.shipped_at,"
          + "s.latest_node,s.latest_event_code,s.latest_description,s.latest_occurred_at,"
          + "s.latest_received_at,s.event_count,s.exception_flag,s.exception_count,"
          + "s.last_exception_code,s.last_exception_note,s.last_exception_at,s.status,"
          + "s.promised_transit_days_min,s.promised_transit_days_max,"
          + "s.promised_eta_earliest_at,s.promised_eta_at,s.carrier_eta_at,s.matched_promise_count,"
          + "s.updated_at," + ETA_STATUS_EXPR + " as eta_status";

  private static final String SUBJECT_FROM =
      " from logistics_tracking_subject s"
          + " left join logistics_channel_config c"
          + "   on c.tenant_id = s.tenant_id and c.id = s.channel_id";

  public TrackingSubjectPage subjects(
      String carrierCode,
      String destinationCountry,
      String nodeType,
      String etaStatus,
      String status,
      String keyword,
      boolean exceptionOnly,
      String sort,
      int page,
      int size) {
    int p = Math.max(page, 0);
    int s = size <= 0 ? 50 : Math.min(size, 200);

    List<Object> filters = new ArrayList<>();
    StringBuilder where = new StringBuilder(" where 1=1");

    String carrier = text(carrierCode);
    if (!carrier.isEmpty()) {
      where.append(" and s.carrier_code = ?");
      filters.add(carrier);
    }
    // 目的国是轨迹查询的主筛选维度之一：跨境延误往往是「某渠道在某些国家」
    // 的系统性问题（10.6 时效分析），没有这一维就看不出系统性延误。
    String country = text(destinationCountry).toUpperCase(Locale.ROOT);
    if (!country.isEmpty()) {
      where.append(" and s.destination_country = ?");
      filters.add(country);
    }
    String node = text(nodeType);
    if (!node.isEmpty()) {
      where.append(" and s.latest_node = ?");
      filters.add(node);
    }
    String st = text(status);
    if (!st.isEmpty()) {
      where.append(" and s.status = ?");
      filters.add(st);
    }
    String eta = text(etaStatus);
    if (!eta.isEmpty()) {
      where.append(" and (" + ETA_STATUS_EXPR + ") = ?");
      filters.add(eta);
    }
    if (exceptionOnly) {
      where.append(" and s.exception_flag");
    }
    String kw = text(keyword);
    if (!kw.isEmpty()) {
      where.append(
          " and (s.master_waybill_no ilike ? or s.sales_order_no ilike ?"
              + " or s.outbound_order_no ilike ? or s.source_record_no ilike ?)");
      String like = "%" + escapeLike(kw) + "%";
      filters.add(like);
      filters.add(like);
      filters.add(like);
      filters.add(like);
    }

    long total =
        sql.fetchOne("select count(*)" + SUBJECT_FROM + where, filters.toArray()).get(0, Long.class);

    List<Object> binds = new ArrayList<>(filters);
    binds.add(s);
    binds.add(p * s);
    var rows =
        sql.fetch(
            "select " + SUBJECT_COLUMNS + SUBJECT_FROM + where
                + " order by " + subjectOrderBy(sort) + " limit ? offset ?",
            binds.toArray());

    List<TrackingSubjectRow> items = new ArrayList<>();
    for (Record r : rows) {
      items.add(toSubjectRow(r));
    }
    return new TrackingSubjectPage(
        items, p, s, total, (int) Math.ceil(total / (double) s), summary(where.toString(), filters));
  }

  /** 概览：<b>全量口径</b>，不受列表筛选与分页影响。 */
  public TrackingSubjectSummary overview() {
    return summary("", List.of());
  }

  private static String subjectOrderBy(String sort) {
    return switch (text(sort)) {
      case "LATEST_ASC" -> "s.latest_occurred_at asc nulls last, s.master_waybill_no asc";
      case "CARRIER_ASC" -> "s.carrier_code asc, s.latest_occurred_at desc nulls last";
      case "ETA_ASC" -> "s.promised_eta_at asc nulls last, s.master_waybill_no asc";
      case "UPDATED_ASC" -> "s.updated_at asc, s.master_waybill_no asc";
      default -> "s.latest_occurred_at desc nulls last, s.updated_at desc";
    };
  }

  /**
   * 汇总：真聚合，<b>不受分页影响</b>。
   *
   * <p>用同样的筛选条件但不加 limit/offset —— 这样「这一屏看到的」与「总量」一致，
   * 而不是拿 50 条去推全量。
   */
  private TrackingSubjectSummary summary(String whereSql, List<Object> binds) {
    var counts =
        sql.fetchOne(
            "select count(*) as total_count,"
                + " count(*) filter (where exception_flag) as exception_count,"
                + " count(*) filter (where eta_status = 'DELAYED') as delayed_count,"
                + " count(*) filter (where eta_status = 'AT_RISK') as at_risk_count,"
                + " count(*) filter (where eta_status = 'NO_ETA') as no_eta_count,"
                + " count(*) filter (where eta_status = 'NO_PROMISE') as no_promise_count,"
                + " count(*) filter (where status = 'DELIVERED') as delivered_count,"
                + " count(*) filter (where status = 'ACTIVE') as in_transit_count,"
                + " coalesce(sum(event_count), 0) as event_total"
                + " from (select s.exception_flag, s.status, s.event_count, "
                + ETA_STATUS_EXPR
                + " as eta_status from logistics_tracking_subject s"
                + whereSql
                + ") t",
            binds.toArray());

    List<TrackingCarrierBucket> byCarrier = new ArrayList<>();
    for (Record r :
        sql.fetch(
            "select s.carrier_code, count(*) as subject_count,"
                + " count(*) filter (where s.exception_flag) as exception_count"
                + " from logistics_tracking_subject s"
                + whereSql
                + " group by s.carrier_code order by subject_count desc, s.carrier_code asc",
            binds.toArray())) {
      byCarrier.add(
          new TrackingCarrierBucket(
              r.get("carrier_code", String.class),
              r.get("subject_count", Long.class),
              r.get("exception_count", Long.class)));
    }

    List<TrackingNodeBucket> byNode = new ArrayList<>();
    for (Record r :
        sql.fetch(
            "select s.latest_node as node_type, count(*) as subject_count"
                + " from logistics_tracking_subject s"
                + whereSql
                + " group by s.latest_node order by subject_count desc, s.latest_node asc",
            binds.toArray())) {
      byNode.add(
          new TrackingNodeBucket(
              r.get("node_type", String.class), r.get("subject_count", Long.class)));
    }

    List<TrackingEtaBucket> byEta = new ArrayList<>();
    for (Record r :
        sql.fetch(
            "select eta_status, count(*) as subject_count from (select "
                + ETA_STATUS_EXPR
                + " as eta_status from logistics_tracking_subject s"
                + whereSql
                + ") t group by eta_status order by subject_count desc, eta_status asc",
            binds.toArray())) {
      byEta.add(
          new TrackingEtaBucket(
              r.get("eta_status", String.class), r.get("subject_count", Long.class)));
    }

    return new TrackingSubjectSummary(
        counts.get("total_count", Long.class),
        counts.get("exception_count", Long.class),
        counts.get("delayed_count", Long.class),
        counts.get("at_risk_count", Long.class),
        counts.get("no_eta_count", Long.class),
        counts.get("no_promise_count", Long.class),
        counts.get("delivered_count", Long.class),
        counts.get("in_transit_count", Long.class),
        counts.get("event_total", Long.class),
        byCarrier,
        byNode,
        byEta);
  }

  private TrackingSubjectRow toSubjectRow(Record r) {
    String etaStatus = r.get("eta_status", String.class);
    return new TrackingSubjectRow(
        r.get("id", UUID.class),
        r.get("source_shipment_record_id", UUID.class),
        r.get("source_record_no", String.class),
        r.get("sales_order_no", String.class),
        r.get("outbound_order_no", String.class),
        r.get("warehouse_ref", String.class),
        r.get("carrier_code", String.class),
        r.get("master_waybill_no", String.class),
        r.get("destination_country", String.class),
        r.get("channel_id", UUID.class),
        r.get("channel_code", String.class),
        r.get("channel_name", String.class),
        r.get("gross_weight_kg", BigDecimal.class),
        r.get("service_level", String.class),
        r.get("parcel_count", Integer.class),
        r.get("shipped_at", OffsetDateTime.class),
        r.get("latest_node", String.class),
        nodeLabel(r.get("latest_node", String.class)),
        r.get("latest_event_code", String.class),
        r.get("latest_description", String.class),
        r.get("latest_occurred_at", OffsetDateTime.class),
        r.get("latest_received_at", OffsetDateTime.class),
        r.get("event_count", Integer.class),
        r.get("exception_flag", Boolean.class),
        r.get("exception_count", Integer.class),
        r.get("last_exception_code", String.class),
        r.get("last_exception_note", String.class),
        r.get("last_exception_at", OffsetDateTime.class),
        r.get("status", String.class),
        subjectStatusLabel(r.get("status", String.class)),
        etaStatus,
        etaStatusLabel(etaStatus),
        r.get("promised_eta_earliest_at", OffsetDateTime.class),
        r.get("promised_eta_at", OffsetDateTime.class),
        r.get("promised_transit_days_min", Integer.class),
        r.get("promised_transit_days_max", Integer.class),
        r.get("carrier_eta_at", OffsetDateTime.class),
        r.get("matched_promise_count", Integer.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  // ==================================================================
  // 读：单票轨迹时间线
  // ==================================================================

  public TrackingTimelineDetail subject(String id) {
    UUID subjectId;
    try {
      subjectId = UUID.fromString(id);
    } catch (IllegalArgumentException e) {
      throw notFound();
    }
    var rows =
        sql.fetch(
            "select " + SUBJECT_COLUMNS + SUBJECT_FROM + " where s.tenant_id = ? and s.id = ?",
            currentTenant(), subjectId);
    if (rows.isEmpty()) {
      throw notFound();
    }
    TrackingSubjectRow subject = toSubjectRow(rows.get(0));
    List<CarrierTrackingEventRow> timeline = eventsOf(subjectId);
    return new TrackingTimelineDetail(
        subject, timeline, etaComparison(subject, timeline), boundaryNotes(subject));
  }

  private List<CarrierTrackingEventRow> eventsOf(UUID subjectId) {
    List<CarrierTrackingEventRow> out = new ArrayList<>();
    for (Record r :
        sql.fetch(
            "select id,subject_id,carrier_code,carrier_event_id,node_type,event_code,description,"
                + " location,occurred_at,received_at,estimated_delivery_at,source,exception_code,"
                + " exception_note,registered_by_membership_id,created_at"
                + " from logistics_tracking_event"
                + " where tenant_id = ? and subject_id = ?"
                + " order by occurred_at desc, received_at desc, created_at desc",
            currentTenant(), subjectId)) {
      out.add(toEventRow(r));
    }
    return out;
  }

  private CarrierTrackingEventRow toEventRow(Record r) {
    String exceptionCode = r.get("exception_code", String.class);
    return new CarrierTrackingEventRow(
        r.get("id", UUID.class),
        r.get("subject_id", UUID.class),
        r.get("carrier_code", String.class),
        r.get("carrier_event_id", String.class),
        r.get("node_type", String.class),
        nodeLabel(r.get("node_type", String.class)),
        r.get("event_code", String.class),
        r.get("description", String.class),
        r.get("location", String.class),
        r.get("occurred_at", OffsetDateTime.class),
        r.get("received_at", OffsetDateTime.class),
        r.get("estimated_delivery_at", OffsetDateTime.class),
        r.get("source", String.class),
        exceptionCode,
        r.get("exception_note", String.class),
        exceptionCode != null && !exceptionCode.isBlank(),
        r.get("registered_by_membership_id", UUID.class),
        r.get("created_at", OffsetDateTime.class));
  }

  /** 事件流（跨票）：真分页 + 真筛选。 */
  public TrackingEventPage events(
      String carrierCode, String nodeType, String subjectId, boolean exceptionOnly, int page, int size) {
    int p = Math.max(page, 0);
    int s = size <= 0 ? 50 : Math.min(size, 200);
    List<Object> filters = new ArrayList<>();
    StringBuilder where = new StringBuilder(" where 1=1");
    String carrier = text(carrierCode);
    if (!carrier.isEmpty()) {
      where.append(" and carrier_code = ?");
      filters.add(carrier);
    }
    String node = text(nodeType);
    if (!node.isEmpty()) {
      where.append(" and node_type = ?");
      filters.add(node);
    }
    String sid = text(subjectId);
    if (!sid.isEmpty()) {
      where.append(" and subject_id = ?");
      try {
        filters.add(UUID.fromString(sid));
      } catch (IllegalArgumentException e) {
        filters.add(UUID.randomUUID()); // 不存在 ⇒ 零行，而不是 500
      }
    }
    if (exceptionOnly) {
      where.append(" and btrim(exception_code) <> ''");
    }
    long total =
        sql.fetchOne("select count(*) from logistics_tracking_event" + where, filters.toArray())
            .get(0, Long.class);
    List<Object> binds = new ArrayList<>(filters);
    binds.add(s);
    binds.add(p * s);
    var rows =
        sql.fetch(
            "select id,subject_id,carrier_code,carrier_event_id,node_type,event_code,description,"
                + " location,occurred_at,received_at,estimated_delivery_at,source,exception_code,"
                + " exception_note,registered_by_membership_id,created_at"
                + " from logistics_tracking_event"
                + where
                + " order by occurred_at desc, received_at desc, created_at desc limit ? offset ?",
            binds.toArray());
    List<CarrierTrackingEventRow> items = new ArrayList<>();
    for (Record r : rows) {
      items.add(toEventRow(r));
    }
    return new TrackingEventPage(items, p, s, total, (int) Math.ceil(total / (double) s));
  }

  // ==================================================================
  // 写：从上游 9.4.1.2 同步运单集合（幂等投影）
  // ==================================================================

  /**
   * 同步上游 9.4.1.2 的运单集合，落成轨迹主体。
   *
   * <p>上游不可达时这里抛 503（理由写「读不到」），**不是**返回空清单 ——
   * 空清单在界面上与「上游真的没有运单」无法区分。
   */
  public TrackingSyncResult sync(TrackingSyncInput input, String bearer) {
    int page = Math.max(input.page() == null ? 0 : input.page(), 0);
    int size = input.size() == null ? 50 : Math.min(Math.max(input.size(), 1), 200);
    String carrier = text(input.carrierCode());
    String serviceLevel =
        input.serviceLevel() == null || input.serviceLevel().isBlank()
            ? "STANDARD"
            : input.serviceLevel().trim().toUpperCase(Locale.ROOT);
    if (!SERVICE_LEVELS.contains(serviceLevel)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "服务等级只能是 ECONOMY / STANDARD / EXPRESS");
    }

    var upstream = shipments.records(carrier.isEmpty() ? null : carrier, page, size, bearer);

    int created = 0;
    int updated = 0;
    int skipped = 0;
    List<String> notes = new ArrayList<>();
    for (var record : upstream.items()) {
      if (record.id() == null || text(record.masterWaybillNo()).isEmpty()) {
        skipped++;
        notes.add("上游有一条发货记录没有运单号，轨迹无法按运单号归集，已跳过");
        continue;
      }
      boolean inserted = upsertSubject(record, serviceLevel);
      if (inserted) {
        created++;
      } else {
        updated++;
      }
    }
    return new TrackingSyncResult(
        upstream.items().size(), created, updated, skipped, upstream.totalElements(), notes);
  }

  /**
   * 幂等写入一个轨迹主体。
   *
   * <p>ON CONFLICT DO UPDATE **只更新身份与承诺快照**，
   * 刻意<b>不碰</b>派生列（latest_node / event_count / exception_*）——
   * 那些由 {@link #refreshSubject} 从事件账本重算，
   * 同步时覆盖它们等于「同步一次就把轨迹历史抹了」。
   */
  private boolean upsertSubject(
      ShipmentTrackingQueryPort.ShipmentRecordView record, String serviceLevel) {
    String country = text(record.destinationCountry()).toUpperCase(Locale.ROOT);
    BigDecimal weightKg = gramsToKg(record.totalGrossWeightG());
    Promise promise = resolvePromise(record.carrierCode(), country, serviceLevel, weightKg, record.shippedAt());

    List<Object> binds = new ArrayList<>();
    binds.add(Ids.next());
    binds.add(currentTenant());
    binds.add(record.id());
    binds.add(text(record.recordNo()));
    binds.add(text(record.salesOrderNo()));
    binds.add(text(record.outboundOrderNo()));
    binds.add(text(record.warehouseRef()));
    binds.add(text(record.carrierCode()));
    binds.add(text(record.masterWaybillNo()));
    binds.add(country);
    binds.add(promise.channelId());
    binds.add(weightKg);
    binds.add(serviceLevel);
    binds.add(record.parcelCount());
    binds.add(record.shippedAt());
    binds.add(promise.transitDaysMin());
    binds.add(promise.transitDaysMax());
    binds.add(promise.etaEarliestAt());
    binds.add(promise.etaAt());
    binds.add(promise.matchedCount());

    var inserted =
        sql.fetch(
            "insert into logistics_tracking_subject ("
                + " id,tenant_id,source_shipment_record_id,source_record_no,sales_order_no,"
                + " outbound_order_no,warehouse_ref,carrier_code,master_waybill_no,"
                + " destination_country,channel_id,gross_weight_kg,service_level,parcel_count,"
                + " shipped_at,promised_transit_days_min,promised_transit_days_max,"
                + " promised_eta_earliest_at,promised_eta_at,matched_promise_count,created_at,updated_at)"
                + " values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::timestamptz,?,?,?::timestamptz,?::timestamptz,?,now(),now())"
                + " on conflict (tenant_id, source_shipment_record_id) do update set"
                + " source_record_no=excluded.source_record_no,"
                + " sales_order_no=excluded.sales_order_no,"
                + " outbound_order_no=excluded.outbound_order_no,"
                + " warehouse_ref=excluded.warehouse_ref,"
                + " carrier_code=excluded.carrier_code,"
                + " master_waybill_no=excluded.master_waybill_no,"
                + " destination_country=excluded.destination_country,"
                + " channel_id=excluded.channel_id,"
                + " gross_weight_kg=excluded.gross_weight_kg,"
                + " service_level=excluded.service_level,"
                + " parcel_count=excluded.parcel_count,"
                + " shipped_at=excluded.shipped_at,"
                + " promised_transit_days_min=excluded.promised_transit_days_min,"
                + " promised_transit_days_max=excluded.promised_transit_days_max,"
                + " promised_eta_earliest_at=excluded.promised_eta_earliest_at,"
                + " promised_eta_at=excluded.promised_eta_at,"
                + " matched_promise_count=excluded.matched_promise_count,"
                + " updated_at=now()"
                + " returning id, (xmax = 0) as inserted",
            binds.toArray());
    return inserted.isEmpty() || Boolean.TRUE.equals(inserted.get(0).get("inserted", Boolean.class));
  }

  /** 克 → 公斤，**用 BigDecimal 除法**（禁浮点）。 */
  private static BigDecimal gramsToKg(BigDecimal grams) {
    if (grams == null) {
      return BigDecimal.ZERO.setScale(6, RoundingMode.HALF_UP);
    }
    return grams.divide(BigDecimal.valueOf(1000), 6, RoundingMode.HALF_UP);
  }

  /** 承诺时效的解析结果。 */
  private record Promise(
      UUID channelId,
      Integer transitDaysMin,
      Integer transitDaysMax,
      OffsetDateTime etaEarliestAt,
      OffsetDateTime etaAt,
      int matchedCount) {}

  /**
   * 解析「这票货的时效承诺」。
   *
   * <p>口径：<b>承运商 × 目的国 × 服务等级 × 重量段</b>命中 10.1.1.3 的
   * {@code logistics_transit_rule}，且该档期在<b>发货时刻</b>生效。
   *
   * <p>命中多条时取<b>承诺最紧</b>的一条（{@code transit_days_max} 最小），
   * 并把命中条数带出去 —— 同一承运商多个渠道都承诺了这个国家与重量段时，
   * 这个歧义必须看得见，不能藏。
   *
   * <p>没有发货时刻就<b>不算</b>承诺：承诺是「发货后 N 天到」，
   * 不知道什么时候发的货就没有起算点。
   */
  private Promise resolvePromise(
      String carrierCode,
      String countryCode,
      String serviceLevel,
      BigDecimal weightKg,
      OffsetDateTime shippedAt) {
    if (shippedAt == null
        || text(carrierCode).isEmpty()
        || countryCode.isEmpty()
        || weightKg == null) {
      return new Promise(null, null, null, null, null, 0);
    }
    var rows =
        sql.fetch(
            "select r.channel_id, r.transit_days_min, r.transit_days_max,"
                + " count(*) over () as matched_count"
                + " from logistics_transit_rule r"
                + " join logistics_channel_config c"
                + "   on c.tenant_id = r.tenant_id and c.id = r.channel_id"
                + " join carrier_logistics_profile p"
                + "   on p.tenant_id = c.tenant_id and p.id = c.carrier_profile_id"
                + " where r.tenant_id = ? and p.party_code = ? and r.country_code = ?"
                + "   and r.service_level = ? and r.status = 'ACTIVE' and r.readiness = 'READY'"
                + "   and (r.effective_from is null or r.effective_from <= ?::timestamptz)"
                + "   and (r.effective_to is null or r.effective_to > ?::timestamptz)"
                + "   and ? >= r.weight_min_kg"
                + "   and (r.weight_max_kg is null or ? < r.weight_max_kg)"
                + " order by r.transit_days_max asc, r.transit_days_min asc, c.channel_code asc"
                + " limit 1",
            currentTenant(),
            text(carrierCode),
            countryCode,
            serviceLevel,
            shippedAt,
            shippedAt,
            weightKg,
            weightKg);
    if (rows.isEmpty()) {
      return new Promise(null, null, null, null, null, 0);
    }
    Record r = rows.get(0);
    int daysMin = r.get("transit_days_min", Integer.class);
    int daysMax = r.get("transit_days_max", Integer.class);
    int matched = r.get("matched_count", Integer.class);
    return new Promise(
        r.get("channel_id", UUID.class),
        daysMin,
        daysMax,
        shippedAt.plusDays(daysMin),
        shippedAt.plusDays(daysMax),
        matched);
  }

  // ==================================================================
  // 写：承运商推送事件（去重的落点）
  // ==================================================================

  /**
   * 承运商推送轨迹事件（批量）。
   *
   * <p>去重：{@code on conflict (tenant_id, carrier_code, carrier_event_id) do nothing}。
   * 服务层的这层「友好」是<b>第二道</b>闸门；<b>第一道</b>是 V212 的唯一约束 ——
   * 绕过服务层直接 INSERT 同一条事件会撞 23505。
   *
   * <p>被拒的条目<b>逐条如实列出</b>（运单不在本租户 / 节点类型未知 / 异常节点缺异常码），
   * 而不是整批静默丢弃。
   */
  public TrackingEventPushResult push(TrackingEventPushInput input, UUID membershipId) {
    String carrier = text(input.carrierCode());
    List<TrackingEventPushRejection> rejections = new ArrayList<>();
    int inserted = 0;
    int duplicated = 0;
    UUID lastSubjectId = null;
    OffsetDateTime receivedAt = OffsetDateTime.now(ZoneOffset.UTC).withNano(0);

    for (TrackingEventPushItem item : input.events()) {
      String eventId = text(item.carrierEventId());
      String waybill = text(item.waybillNo());
      String nodeType = text(item.nodeType()).toUpperCase(Locale.ROOT);

      if (!NODE_TYPES.contains(nodeType)) {
        rejections.add(
            new TrackingEventPushRejection(eventId, waybill, "未知节点类型：" + text(item.nodeType())));
        continue;
      }
      UUID subjectId = subjectIdOf(carrier, waybill);
      if (subjectId == null) {
        rejections.add(
            new TrackingEventPushRejection(
                eventId, waybill, "运单不在本租户的轨迹主体里（先同步运单集合）"));
        continue;
      }
      String exceptionCode = text(item.exceptionCode());
      if (nodeType.equals("EXCEPTION") && exceptionCode.isEmpty()) {
        rejections.add(
            new TrackingEventPushRejection(
                eventId, waybill, "异常节点必须带异常码：标了异常却说不清是什么异常"));
        continue;
      }

      int affected =
          sql.execute(
              "insert into logistics_tracking_event ("
                  + " id,tenant_id,subject_id,carrier_code,carrier_event_id,node_type,event_code,"
                  + " description,location,occurred_at,received_at,estimated_delivery_at,source,"
                  + " exception_code,exception_note,payload,registered_by_membership_id,created_at)"
                  // 18 列 / 17 个绑定 + now()：`?::jsonb` 必须**正好**落在 payload 上，
                  // 多写一个普通 `?` 会让它挪到 registered_by_membership_id 头上 ——
                  // 症状是 jOOQ 报「未设定参数值 18 的内容」。
                  + " values (?,?,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz,?::timestamptz,?,?,?,"
                  + " ?::jsonb,?,now())"
                  + " on conflict (tenant_id, carrier_code, carrier_event_id) do nothing",
              Ids.next(),
              currentTenant(),
              subjectId,
              carrier,
              eventId,
              nodeType,
              text(item.eventCode()),
              text(item.description()),
              text(item.location()),
              item.occurredAt(),
              receivedAt,
              item.estimatedDeliveryAt(),
              "CARRIER_PUSH",
              exceptionCode,
              text(item.exceptionNote()),
              payloadOf(item),
              membershipId);
      if (affected > 0) {
        inserted++;
      } else {
        duplicated++;
      }
      lastSubjectId = subjectId;
    }

    if (lastSubjectId != null) {
      refreshSubject(lastSubjectId);
    }
    TrackingSubjectRow subject =
        lastSubjectId == null ? null : subjectRowOf(lastSubjectId);
    return new TrackingEventPushResult(
        carrier, input.events().size(), inserted, duplicated, rejections.size(), rejections, subject);
  }

  private UUID subjectIdOf(String carrierCode, String waybillNo) {
    var rows =
        sql.fetch(
            "select id from logistics_tracking_subject"
                + " where tenant_id = ? and carrier_code = ? and master_waybill_no = ?"
                + " order by shipped_at desc nulls last, created_at desc limit 1",
            currentTenant(), text(carrierCode), text(waybillNo));
    return rows.isEmpty() ? null : rows.get(0).get("id", UUID.class);
  }

  private TrackingSubjectRow subjectRowOf(UUID subjectId) {
    var rows =
        sql.fetch(
            "select " + SUBJECT_COLUMNS + SUBJECT_FROM + " where s.tenant_id = ? and s.id = ?",
            currentTenant(), subjectId);
    return rows.isEmpty() ? null : toSubjectRow(rows.get(0));
  }

  /** 原始凭据：整条事件报文的 JSON。带出处才可复核。 */
  private String payloadOf(Object item) {
    try {
      return json.writeValueAsString(item);
    } catch (JsonProcessingException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "事件凭据序列化失败", e);
    }
  }

  /**
   * 从事件账本重算主体的派生状态。
   *
   * <p>派生状态<b>只有一个来源</b>：事件账本。同步运单集合时**不碰**这些列，
   * 避免「同步一次就把轨迹历史抹了」。
   */
  private void refreshSubject(UUID subjectId) {
    sql.execute(
        "update logistics_tracking_subject s set"
            + " latest_node = coalesce(l.node_type, 'NO_TRACE'),"
            + " latest_event_code = coalesce(l.event_code, ''),"
            + " latest_description = coalesce(l.description, ''),"
            + " latest_occurred_at = l.occurred_at,"
            + " latest_received_at = l.received_at,"
            + " event_count = st.event_count,"
            + " exception_flag = (st.exception_count > 0),"
            + " exception_count = st.exception_count,"
            + " last_exception_code = coalesce(le.exception_code, ''),"
            + " last_exception_note = coalesce(le.exception_note, ''),"
            + " last_exception_at = le.occurred_at,"
            + " carrier_eta_at = coalesce(et.estimated_delivery_at, s.carrier_eta_at),"
            + " status = case when l.node_type = 'DELIVERED' then 'DELIVERED'"
            + "              when l.node_type = 'RETURNED' then 'RETURNED'"
            + "              else 'ACTIVE' end,"
            + " updated_at = now()"
            + " from (select count(*) as event_count,"
            + "         count(*) filter (where btrim(exception_code) <> '') as exception_count"
            + "         from logistics_tracking_event e"
            + "        where e.tenant_id = ? and e.subject_id = ?) st"
            + " left join lateral (select e.node_type, e.event_code, e.description,"
            + "                          e.occurred_at, e.received_at"
            + "                     from logistics_tracking_event e"
            + "                    where e.tenant_id = ? and e.subject_id = ?"
            + "                    order by e.occurred_at desc, e.received_at desc, e.created_at desc"
            + "                    limit 1) l on true"
            + " left join lateral (select e.exception_code, e.exception_note, e.occurred_at"
            + "                     from logistics_tracking_event e"
            + "                    where e.tenant_id = ? and e.subject_id = ?"
            + "                      and btrim(e.exception_code) <> ''"
            + "                    order by e.occurred_at desc, e.received_at desc"
            + "                    limit 1) le on true"
            + " left join lateral (select e.estimated_delivery_at"
            + "                     from logistics_tracking_event e"
            + "                    where e.tenant_id = ? and e.subject_id = ?"
            + "                      and e.estimated_delivery_at is not null"
            + "                    order by e.occurred_at desc, e.received_at desc"
            + "                    limit 1) et on true"
            + " where s.tenant_id = ? and s.id = ?",
        currentTenant(),
        subjectId,
        currentTenant(),
        subjectId,
        currentTenant(),
        subjectId,
        currentTenant(),
        subjectId,
        currentTenant(),
        subjectId);
  }

  // ==================================================================
  // 写：按承运商批量拉取（轮询任务）
  // ==================================================================

  /**
   * 按承运商批量拉取：建一轮任务并执行。
   *
   * <p>本方法<b>只提供入口</b>，不自带调度器（理由见 capabilities 的 polling_scheduler 段）。
   *
   * <p>没有已接通的 vendor 实现时，这一轮<b>如实落 NO_CHANNEL</b> 并写明理由 ——
   * 绝不返回空轨迹冒充「承运商说这一票没有轨迹」。
   */
  public TrackingPullTaskDetail createPullTask(TrackingPullTaskInput input, UUID membershipId) {
    String carrier = text(input.carrierCode());
    String triggerSource =
        input.triggerSource() == null || input.triggerSource().isBlank()
            ? "MANUAL"
            : input.triggerSource().trim().toUpperCase(Locale.ROOT);
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).withNano(0);

    List<String> waybills = resolveWaybills(carrier, input.waybillNos());
    UUID taskId = Ids.next();
    String taskNo = nextTaskNo(now);
    sql.execute(
        "insert into logistics_tracking_pull_task ("
            + " id,tenant_id,task_no,carrier_code,status,trigger_source,requested_count,"
            + " created_by_membership_id,created_at)"
            + " values (?,?,?,?,'PENDING',?,?,?,now())",
        taskId,
        currentTenant(),
        taskNo,
        carrier,
        triggerSource,
        waybills.size(),
        membershipId);

    var resolution = adapters.resolve(carrier);
    if (resolution instanceof CarrierTrackingAdapterRegistry.Resolution.Unavailable unavailable) {
      sql.execute(
          "update logistics_tracking_pull_task set status='NO_CHANNEL',failure_reason=?,"
              + " started_at=?::timestamptz,finished_at=?::timestamptz where tenant_id=? and id=?",
          unavailable.reason(), now, now, currentTenant(), taskId);
      for (String waybill : waybills) {
        insertPullItem(taskId, carrier, subjectIdOf(carrier, waybill), waybill, "FAILED", 0, 0, unavailable.reason());
      }
      return pullTask(taskId.toString());
    }

    CarrierTrackingAdapter adapter =
        ((CarrierTrackingAdapterRegistry.Resolution.Connected) resolution).adapter();
    sql.execute(
        "update logistics_tracking_pull_task set status='RUNNING',started_at=?::timestamptz where tenant_id=? and id=?",
        now, currentTenant(), taskId);

    CarrierTrackingAdapter.PullOutcome outcome;
    try {
      outcome = adapter.pull(new CarrierTrackingAdapter.TrackingQuery(carrier, waybills));
    } catch (CarrierTrackingAdapter.CarrierTrackingException e) {
      String reason = e.getMessage() == null ? "承运商轨迹拉取失败" : e.getMessage();
      String status = e.connectionFailure() ? "NO_CHANNEL" : "FAILED";
      sql.execute(
          "update logistics_tracking_pull_task set status=?,failure_reason=?,finished_at=?::timestamptz"
              + " where tenant_id=? and id=?",
          status, reason, now, currentTenant(), taskId);
      for (String waybill : waybills) {
        insertPullItem(taskId, carrier, subjectIdOf(carrier, waybill), waybill, "FAILED", 0, 0, reason);
      }
      return pullTask(taskId.toString());
    }

    // 按运单号归集事件：同一票可能有多条
    Map<String, List<CarrierTrackingAdapter.CarrierEvent>> byWaybill = new LinkedHashMap<>();
    for (CarrierTrackingAdapter.CarrierEvent event : outcome.events()) {
      byWaybill.computeIfAbsent(text(event.waybillNo()), k -> new ArrayList<>()).add(event);
    }

    int insertedTotal = 0;
    int duplicateTotal = 0;
    int noEventCount = 0;
    int failedCount = 0;
    int succeededCount = 0;
    List<UUID> touched = new ArrayList<>();

    for (String waybill : waybills) {
      List<CarrierTrackingAdapter.CarrierEvent> events = byWaybill.get(waybill);
      if (events == null || events.isEmpty()) {
        if (outcome.noEventWaybills() != null && outcome.noEventWaybills().contains(waybill)) {
          noEventCount++;
          insertPullItem(
              taskId, carrier, null, waybill, "NO_EVENT", 0, 0, "承运商明确回复没有轨迹");
        } else {
          failedCount++;
          insertPullItem(
              taskId, carrier, null, waybill, "FAILED", 0, 0, "承运商返回没有覆盖这一票");
        }
        continue;
      }
      UUID subjectId = subjectIdOf(carrier, waybill);
      if (subjectId == null) {
        failedCount++;
        insertPullItem(
            taskId, carrier, null, waybill, "FAILED", 0, 0, "运单不在本租户的轨迹主体里");
        continue;
      }
      int inserted = 0;
      int duplicated = 0;
      for (CarrierTrackingAdapter.CarrierEvent event : events) {
        int affected = insertPulledEvent(subjectId, carrier, event, now);
        if (affected > 0) {
          inserted++;
        } else {
          duplicated++;
        }
      }
      insertedTotal += inserted;
      duplicateTotal += duplicated;
      touched.add(subjectId);
      if (inserted > 0) {
        succeededCount++;
        insertPullItem(taskId, carrier, subjectId, waybill, "PULLED", inserted, duplicated, "");
      } else {
        insertPullItem(
            taskId, carrier, subjectId, waybill, "DUPLICATE", 0, duplicated,
            "这一票的事件全部重复，未产生新记录");
      }
    }

    for (UUID subjectId : touched) {
      refreshSubject(subjectId);
    }

    String status;
    if (failedCount == 0) {
      status = "SUCCEEDED";
    } else if (succeededCount > 0 || noEventCount > 0) {
      status = "PARTIAL";
    } else {
      status = "FAILED";
    }
    sql.execute(
        "update logistics_tracking_pull_task set status=?,succeeded_count=?,duplicate_count=?,"
            + " no_event_count=?,failed_count=?,inserted_event_count=?,finished_at=?::timestamptz"
            + " where tenant_id=? and id=?",
        status,
        succeededCount,
        duplicateTotal,
        noEventCount,
        failedCount,
        insertedTotal,
        now,
        currentTenant(),
        taskId);
    return pullTask(taskId.toString());
  }

  private int insertPulledEvent(
      UUID subjectId, String carrier, CarrierTrackingAdapter.CarrierEvent event, OffsetDateTime receivedAt) {
    String nodeType = text(event.nodeType()).toUpperCase(Locale.ROOT);
    if (!NODE_TYPES.contains(nodeType)) {
      return 0;
    }
    String exceptionCode = text(event.exceptionCode());
    if (nodeType.equals("EXCEPTION") && exceptionCode.isEmpty()) {
      return 0;
    }
    return sql.execute(
        "insert into logistics_tracking_event ("
            + " id,tenant_id,subject_id,carrier_code,carrier_event_id,node_type,event_code,"
            + " description,location,occurred_at,received_at,estimated_delivery_at,source,"
            + " exception_code,exception_note,payload,created_at)"
            + " values (?,?,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz,?::timestamptz,'CARRIER_PULL',"
            + " ?,?,?::jsonb,now())"
            + " on conflict (tenant_id, carrier_code, carrier_event_id) do nothing",
        Ids.next(),
        currentTenant(),
        subjectId,
        carrier,
        text(event.carrierEventId()),
        nodeType,
        text(event.eventCode()),
        text(event.description()),
        text(event.location()),
        event.occurredAt(),
        receivedAt,
        event.estimatedDeliveryAt(),
        exceptionCode,
        text(event.exceptionNote()),
        payloadOf(event));
  }

  private void insertPullItem(
      UUID taskId,
      String carrier,
      UUID subjectId,
      String waybill,
      String status,
      int inserted,
      int duplicated,
      String detail) {
    sql.execute(
        "insert into logistics_tracking_pull_task_item ("
            + " id,tenant_id,task_id,subject_id,waybill_no,status,inserted_event_count,"
            + " duplicate_event_count,detail)"
            + " values (?,?,?,?,?,?,?,?,?)"
            + " on conflict (tenant_id, task_id, waybill_no) do nothing",
        Ids.next(),
        currentTenant(),
        taskId,
        subjectId,
        waybill,
        status,
        inserted,
        duplicated,
        text(detail));
  }

  /** 拉取范围：给了运单号就用它，没给就取该承运商下<b>全部</b>在跟踪的运单（上限 500）。 */
  private List<String> resolveWaybills(String carrierCode, List<String> requested) {
    List<String> out = new ArrayList<>();
    if (requested != null && !requested.isEmpty()) {
      for (String waybill : requested) {
        String value = text(waybill);
        if (!value.isEmpty()) {
          out.add(value);
        }
      }
      return out;
    }
    for (Record r :
        sql.fetch(
            "select master_waybill_no from logistics_tracking_subject"
                + " where tenant_id = ? and carrier_code = ?"
                + " order by latest_occurred_at desc nulls last limit 500",
            currentTenant(), carrierCode)) {
      out.add(r.get(0, String.class));
    }
    return out;
  }

  private String nextTaskNo(OffsetDateTime at) {
    Long n = sql.fetchOne("select nextval('logistics_tracking_pull_task_no_seq')").get(0, Long.class);
    return String.format(
        Locale.ROOT, "TRK-%s%06d",
        at.atZoneSameInstant(ZoneOffset.UTC).toLocalDate().toString().replace("-", ""), n);
  }

  public TrackingPullTaskPage pullTasks(String carrierCode, String status, int page, int size) {
    int p = Math.max(page, 0);
    int s = size <= 0 ? 50 : Math.min(size, 200);
    List<Object> filters = new ArrayList<>();
    StringBuilder where = new StringBuilder(" where 1=1");
    String carrier = text(carrierCode);
    if (!carrier.isEmpty()) {
      where.append(" and carrier_code = ?");
      filters.add(carrier);
    }
    String st = text(status);
    if (!st.isEmpty()) {
      where.append(" and status = ?");
      filters.add(st);
    }
    long total =
        sql.fetchOne("select count(*) from logistics_tracking_pull_task" + where, filters.toArray())
            .get(0, Long.class);
    List<Object> binds = new ArrayList<>(filters);
    binds.add(s);
    binds.add(p * s);
    var rows =
        sql.fetch(
            "select id,task_no,carrier_code,status,trigger_source,requested_count,succeeded_count,"
                + " duplicate_count,no_event_count,failed_count,inserted_event_count,started_at,"
                + " finished_at,failure_reason,created_by_membership_id,created_at"
                + " from logistics_tracking_pull_task"
                + where
                + " order by created_at desc, id desc limit ? offset ?",
            binds.toArray());
    List<TrackingPullTaskRow> items = new ArrayList<>();
    for (Record r : rows) {
      items.add(toPullTaskRow(r));
    }
    return new TrackingPullTaskPage(items, p, s, total, (int) Math.ceil(total / (double) s));
  }

  public TrackingPullTaskDetail pullTask(String id) {
    UUID taskId;
    try {
      taskId = UUID.fromString(id);
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "拉取任务不存在");
    }
    var rows =
        sql.fetch(
            "select id,task_no,carrier_code,status,trigger_source,requested_count,succeeded_count,"
                + " duplicate_count,no_event_count,failed_count,inserted_event_count,started_at,"
                + " finished_at,failure_reason,created_by_membership_id,created_at"
                + " from logistics_tracking_pull_task where tenant_id = ? and id = ?",
            currentTenant(), taskId);
    if (rows.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "拉取任务不存在");
    }
    List<TrackingPullTaskItemRow> items = new ArrayList<>();
    for (Record r :
        sql.fetch(
            "select id,subject_id,waybill_no,status,inserted_event_count,duplicate_event_count,detail"
                + " from logistics_tracking_pull_task_item where tenant_id = ? and task_id = ?"
                + " order by waybill_no asc",
            currentTenant(), taskId)) {
      items.add(
          new TrackingPullTaskItemRow(
              r.get("id", UUID.class),
              r.get("subject_id", UUID.class),
              r.get("waybill_no", String.class),
              r.get("status", String.class),
              pullItemStatusLabel(r.get("status", String.class)),
              r.get("inserted_event_count", Integer.class),
              r.get("duplicate_event_count", Integer.class),
              r.get("detail", String.class)));
    }
    return new TrackingPullTaskDetail(toPullTaskRow(rows.get(0)), items);
  }

  private TrackingPullTaskRow toPullTaskRow(Record r) {
    return new TrackingPullTaskRow(
        r.get("id", UUID.class),
        r.get("task_no", String.class),
        r.get("carrier_code", String.class),
        r.get("status", String.class),
        pullTaskStatusLabel(r.get("status", String.class)),
        r.get("trigger_source", String.class),
        r.get("requested_count", Integer.class),
        r.get("succeeded_count", Integer.class),
        r.get("duplicate_count", Integer.class),
        r.get("no_event_count", Integer.class),
        r.get("failed_count", Integer.class),
        r.get("inserted_event_count", Integer.class),
        r.get("started_at", OffsetDateTime.class),
        r.get("finished_at", OffsetDateTime.class),
        r.get("failure_reason", String.class),
        r.get("created_by_membership_id", UUID.class),
        r.get("created_at", OffsetDateTime.class));
  }

  // ==================================================================
  // 时效对比与边界说明
  // ==================================================================

  private TrackingEtaComparison etaComparison(
      TrackingSubjectRow subject, List<CarrierTrackingEventRow> timeline) {
    String note;
    if (subject.promisedEtaAt() == null) {
      note =
          "没有命中时效承诺（承运商 × 目的国 × 服务等级 × 重量段）："
              + "我方没有可比的承诺，所以判不了延误";
    } else if (subject.matchedPromiseCount() > 1) {
      note =
          "命中 "
              + subject.matchedPromiseCount()
              + " 条时效承诺，已取**承诺最紧**的一条判定；渠道口径需要 10.1.1.2 收敛";
    } else if (subject.carrierEtaAt() == null) {
      note = "承运商还没给预计到达时刻，只能看我方承诺";
    } else {
      note = "我方承诺（时效规则）与承运商预计（承运商报文）是两个来源，不合并";
    }
    return new TrackingEtaComparison(
        subject.etaStatus(),
        subject.etaStatusLabel(),
        subject.promisedTransitDaysMin(),
        subject.promisedTransitDaysMax(),
        subject.promisedEtaEarliestAt(),
        subject.promisedEtaAt(),
        subject.carrierEtaAt(),
        subject.latestOccurredAt(),
        note,
        subject.matchedPromiseCount());
  }

  private List<String> boundaryNotes(TrackingSubjectRow subject) {
    List<String> notes = new ArrayList<>();
    notes.add(
        "运单真值在 trade（9.4.1.2），本页是它的一份投影 + 本服务自持的轨迹事件账本；"
            + "本页不写回上游。");
    notes.add(
        "轨迹按承运商事件 ID 在 DB 层去重：同一节点重复推送不产生重复记录，"
            + "而事件本身不可改写（append-only）。");
    if (subject.exceptionFlag()) {
      notes.add(
          "这一票有异常节点（最近一次："
              + (subject.lastExceptionCode().isEmpty() ? "未给异常码" : subject.lastExceptionCode())
              + "）—— 本页**只标记**；联系承运商 / 理赔 / 补发 / 改址归 10.3.1.2。");
    } else {
      notes.add("本票当前没有异常节点。异常**处置**归 10.3.1.2（未交付），本页不提供处置入口。");
    }
    if (subject.matchedPromiseCount() > 1) {
      notes.add(
          "同一承运商有 "
              + subject.matchedPromiseCount()
              + " 条渠道时效承诺命中这一组口径，已取承诺最紧的一条；"
              + "渠道口径收敛归 10.1.1.2。");
    }
    notes.add("本页**不涉及任何金额**：运费成本归集与结算归 12 财务（未交付）。");
    return notes;
  }

  // ==================================================================
  // 标签
  // ==================================================================

  static String nodeLabel(String nodeType) {
    return switch (nodeType == null ? "" : nodeType) {
      case "NO_TRACE" -> "无轨迹";
      case "PICKED_UP" -> "已揽收";
      case "IN_TRANSIT" -> "运输中";
      case "CUSTOMS" -> "清关中";
      case "TRANSFER" -> "转运中";
      case "OUT_FOR_DELIVERY" -> "派送中";
      case "DELIVERED" -> "已签收";
      case "EXCEPTION" -> "异常";
      case "RETURNED" -> "已退回";
      default -> nodeType;
    };
  }

  static String etaStatusLabel(String etaStatus) {
    return switch (etaStatus == null ? "" : etaStatus) {
      case "ON_TIME" -> "按时";
      case "AT_RISK" -> "有风险（承运商预计晚于承诺）";
      case "DELAYED" -> "已延误";
      case "NO_PROMISE" -> "无时效承诺（判不了延误）";
      case "NO_ETA" -> "承运商未给预计";
      default -> etaStatus;
    };
  }

  static String subjectStatusLabel(String status) {
    return switch (status == null ? "" : status) {
      case "ACTIVE" -> "在途";
      case "DELIVERED" -> "已签收";
      case "RETURNED" -> "已退回";
      case "VOID" -> "已作废";
      default -> status;
    };
  }

  static String pullTaskStatusLabel(String status) {
    return switch (status == null ? "" : status) {
      case "PENDING" -> "待执行";
      case "RUNNING" -> "执行中";
      case "SUCCEEDED" -> "全部取回";
      case "PARTIAL" -> "部分失败";
      case "FAILED" -> "整体失败";
      case "NO_CHANNEL" -> "承运商轨迹通道未接通";
      default -> status;
    };
  }

  static String pullItemStatusLabel(String status) {
    return switch (status == null ? "" : status) {
      case "PULLED" -> "已取回";
      case "DUPLICATE" -> "全部重复";
      case "NO_EVENT" -> "承运商回复无轨迹";
      case "FAILED" -> "拉取失败";
      case "SKIPPED" -> "已跳过";
      default -> status;
    };
  }
}
