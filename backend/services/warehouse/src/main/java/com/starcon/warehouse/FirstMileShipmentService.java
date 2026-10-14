package com.starcon.warehouse;

import static com.starcon.warehouse.FirstMileShipmentModels.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 10.4.1.1 头程发运单。
 *
 * <p>蓝图：「管理国内到海外的货权转移发运：选择货物与批次（国内仓/供应商）、选择运输方式 （海运/空运/铁运/快递）、申报信息、目的仓、预计到港时间、成本预估」； 输出「头程发运单 +
 * 发货分录（转在途）+ 报关资料 + 预计到港时间」； 「<b>头程发运是货权与成本的关键节点</b>：从这一刻起成本转嫁方式（FOB/CIF）与风险承担方确定， 直接影响利润口径」。
 *
 * <p>六条实现纪律：
 *
 * <ol>
 *   <li><b>货权与成本节点入库进契约</b>：{@code incoterm} + {@code riskBearer} 是 NOT NULL 列， 有 DB 级 Incoterms
 *       一致性约束（EXW 只能配 BUYER、DDP 只能配 SELLER）， 全部查询、详情与列表都返回它们 —— 不是页面上写死的展示项。
 *   <li><b>发出只登记在途腿，不动在手</b>：照抄 7.5.1.2 台账原文的头程口径 （「头程/采购登记时不动在手，到货时才入库目的仓」）。本服务**没有一行 SQL** 写
 *       {@code stock_bucket} / {@code inventory_balance} / {@code inventory_entry}。
 *   <li><b>只调 7.5.1.2 的公开端点</b>：{@link InventoryInTransitService#register}， {@code
 *       transitKind=FIRST_MILE}，{@code source_system='FIRST_MILE'}、 {@code
 *       source_reference=发运单号-行号} 指回本单。 {@code inventory_transfer} 与 {@code in_transit_event}
 *       的写入所有者是 7.5.1.2， 直写会越过「每张事实表只有一个写入所有者」并让将来交接双记在途。
 *   <li><b>已发出是终态</b>：在途腿已在 7.5.1.2 名下，回退状态会让货权记录与在途对不上。 要改这批货走 7.5.1.2 的差异登记。
 *   <li><b>同一批货不能发两次</b>：DB 延迟约束触发器 {@code guard_first_mile_over_ship} 钉死 「同一实收批次的累计发运量 ≤ 实收量 −
 *       拒收量」，服务层再给一次可读报错。
 *   <li><b>未交付的来源与下游显式声明</b>：6 采购（36 个模块全未交付）、3.7.1.1 HS 编码字典、 10.5 报关、10.4.2.1 海外仓连接、10.4.1.2
 *       在途跟踪全部在 {@code /capabilities} 里 {@code available=false} + 原因 + {@code ownerModule} + {@code
 *       href}， <b>不建占位表、不返回空集冒充成功</b>，建单直接拒绝未交付的来源。
 * </ol>
 *
 * <p>状态机只有一处真相：{@link ShipmentStatus#allowedNext}。确认、发出、取消三个动作 都不各自拼状态判定表。
 */
@Service
public class FirstMileShipmentService {

  /** 单页最大行数。 */
  static final int MAX_PAGE_SIZE = 200;

  private static final List<String> SORTS =
      List.of("UPDATED_AT", "CREATED_AT", "SHIPMENT_CODE", "EXPECTED_ARRIVE_AT", "DEPART_AT");

  /** 跨服务定位键：与 7.5.1.2 的 source_system 同值，两边不许各写各的。 */
  private static final String SOURCE_SYSTEM = "FIRST_MILE";

  private final DSLContext sql;
  private final InventoryInTransitService inTransit;

  public FirstMileShipmentService(DSLContext sql, InventoryInTransitService inTransit) {
    this.sql = sql;
    this.inTransit = inTransit;
  }

  // ================================================================ 能力

  /**
   * 头程发运能力：来源维度、**未交付依赖逐条声明**、四态状态机、运输方式与贸易术语词表， 以及四条必须如实讲清楚的口径说明。
   *
   * <p>本车道的依赖自检结论（对集成头实际查证，不凭蓝图的「上游 =」假定）： 8.2.1.1 收货（迁移 V70）**已交付**，因此 {@code GOODS_RECEIPT}
   * 来源是真通的 —— 建单时会真去校验来源收货单存在、属于本租户，且每一行指明的实收批次真的属于那张收货单。 其余五个维度在本车道**未交付**，一律显式 {@code
   * available=false}。
   */
  public FirstMileCapabilities capabilities() {
    List<FirstMileDimension> sources =
        List.of(
            new FirstMileDimension(
                "GOODS_RECEIPT",
                ShipmentSourceType.GOODS_RECEIPT.label(),
                true,
                "8.2.1.1",
                "/warehouse/goods-receipts",
                "已交付：8.2.1.1 收货单（迁移 V70）。建单时会真去校验来源收货单存在、属于本租户，"
                    + "且每一行指明的实收批次真的属于那张收货单、SKU 与批次都对得上。"),
            new FirstMileDimension(
                "SUPPLIER_PURCHASE",
                ShipmentSourceType.SUPPLIER_PURCHASE.label(),
                false,
                "6.3.2.2",
                "/purchase/orders",
                "上游 6 采购与供应尚未交付：6.1–6.6 全部 36 个模块均未开工，采购单表与到货端点都不存在。"
                    + "本模块因此不接受 SUPPLIER_PURCHASE 来源的发运单 —— 建一张没有采购单可指的单，"
                    + "会让「这批货的货权从哪家供应商手里转过来」永远答不出来。"));

    List<FirstMileDimension> dependencies =
        List.of(
            new FirstMileDimension(
                "CUSTOMS_FILING",
                "报关单与三单据提交",
                false,
                "10.5.1.1",
                "/logistics/customs-declarations",
                "下游 10.5 报关未交付：报关单、装箱单、发票的生成与提交归 10.5.1.1。"
                    + "本模块只承载**发运时点必须定下的申报信息**（申报品名、HS 编码、货值、"
                    + "原产地、箱数毛净重），作为 10.5.1.1 的直接输入，不代做报关动作。"),
            new FirstMileDimension(
                "HS_CODE_DICTIONARY",
                "HS 编码字典",
                false,
                "3.7.1.1",
                "/products/hs-codes",
                "3.7.1.1 HS 编码与申报要素未交付（台账记为阻塞：product_compliance 复制自 "
                    + "product_research，装不了 HS 编码）。因此本表的 hs_code 接受自由码，"
                    + "**没有**编码字典可校验 —— 不假装有字典，也不给一个点下去没反应的选择器。"),
            new FirstMileDimension(
                "OVERSEAS_WAREHOUSE",
                "海外仓连接与备货预报",
                false,
                "10.4.2.1",
                "/logistics/overseas-warehouses",
                "10.4.2.1 海外仓连接未交付：本模块的「目的仓」取自本租户 warehouse 表里的"
                    + "海外仓行，不是第三方海外仓服务商账户。要接服务商库存同步与仓端回执，"
                    + "等 10.4.2.1 交付后由它补。"),
            new FirstMileDimension(
                "IN_TRANSIT_TRACKING",
                "在途跟踪看板与延误预警",
                false,
                "10.4.1.2",
                "/inventory/transit",
                "10.4.1.2 在途跟踪未交付。发出后这批货的在途余量、到达与短少由 7.5.1.2 "
                    + "在途库存真实持有（/inventory/transit），本模块只如实指过去，不建第二份在途账。"),
            new FirstMileDimension(
                "LANDED_COST",
                "落地成本核算",
                false,
                "12.3.1.4",
                "/finance/landed-cost",
                "12 财务未交付：本单只存发运时点的**成本预估快照**（运费/保险/关税预估/操作费）"
                    + "与申报货值。真实落地成本归集、汇率与分摊归 12.3，本模块不代算。"));

    List<ShipmentTransition> transitions = new ArrayList<>();
    for (var from : ShipmentStatus.values()) {
      var allowed = ShipmentStatus.allowedNext(from);
      for (var to : ShipmentStatus.values()) {
        boolean ok = allowed.contains(to);
        transitions.add(
            new ShipmentTransition(
                from.name(),
                to.name(),
                ok,
                ok
                    ? (from == to ? "允许：改单（状态不变，整份替换明细）" : "允许")
                    : switch (from) {
                      case DISPATCHED -> "已发出是终态：货权已转移、在途腿已在 7.5.1.2 名下。" + "要改这批货请走 7.5.1.2 的差异登记";
                      case CANCELLED -> "已取消是终态";
                      default -> "非法跳转：状态由建单/确认/发出/取消四个动作推导，不接受手工设置";
                    }));
      }
    }

    List<ShipmentStatusOption> statuses = new ArrayList<>();
    for (var s : ShipmentStatus.values()) {
      statuses.add(new ShipmentStatusOption(s.name(), s.label(), countBy("status", s.name())));
    }

    List<TransportModeOption> modes = new ArrayList<>();
    for (var m : TransportMode.values()) {
      modes.add(
          new TransportModeOption(
              m.name(), m.label(), countBy("transport_mode", m.name()), m.typicalTransitDays()));
    }

    List<IncotermOption> incoterms = new ArrayList<>();
    for (var i : Incoterm.values()) {
      var bearers = new ArrayList<String>();
      for (var b : RiskBearer.values()) {
        if (allowedRiskBearer(i, b)) {
          bearers.add(b.name());
        }
      }
      incoterms.add(new IncotermOption(i.name(), i.label(), i.riskPoint(), bearers));
    }

    List<RiskBearerOption> bearers = new ArrayList<>();
    for (var b : RiskBearer.values()) {
      bearers.add(new RiskBearerOption(b.name(), b.label()));
    }

    return new FirstMileCapabilities(
        sources,
        dependencies,
        transitions,
        statuses,
        modes,
        incoterms,
        bearers,
        "在途腿**只经 7.5.1.2 的公开端点登记**（POST /warehouse/inventory/in-transit，"
            + "transitKind=FIRST_MILE，sourceSystem=FIRST_MILE，sourceReference=发运单号-行号），"
            + "本模块从不直写 inventory_transfer / in_transit_event —— 那两张表的写入所有者是 7.5.1.2。"
            + "直写会让两处各记一次在途，补货按 6.2.1.1 扣的就是两倍。",
        "**发出不扣在手**：头程货在发出之前根本没进过我方库存账（它来自供应商或本租户另一张"
            + "收货单，不是从在手扣出来的）。所以发出只登记在途腿，到货入库由 7.5.1.2 的 arrive "
            + "落目的仓并写 RECEIPT 分录。在手余额在到货前不动，这是对的 —— 到货前凭空多出库存更糟。",
        "申报信息按**报关口径**存，不是照搬销售标题：10.5.1.2 原文点名「申报品名不能照搬销售标题，"
            + "两者用途不同（前者为清关，后者为转化），混用是常见被退单原因」。所以申报品名是"
            + "必填独立列；HS 编码因 3.7.1.1 未交付而接受自由码，无字典校验。",
        "成本栏是**发运时点的预估快照**（运费/保险/关税预估/操作费）加各行申报货值，"
            + "用于头程层面的到岸成本估算。真实落地成本、汇率与分摊归 12.3 财务（尚未交付），"
            + "本模块不代算、不回写。货权与成本的关键节点是 incoterm + riskBearer："
            + "DB 层钉死 EXW 只能配 BUYER、DDP 只能配 SELLER。");
  }

  /** Incoterms 与风险承担方的真实一致性。与迁移里的两条 CHECK 同一条纪律。 */
  private static boolean allowedRiskBearer(Incoterm term, RiskBearer bearer) {
    return switch (term) {
      case EXW -> bearer == RiskBearer.BUYER;
      case DDP -> bearer == RiskBearer.SELLER;
      default -> true;
    };
  }

  private long countBy(String column, String value) {
    // 列名只允许白名单里的字面量：它拼进 SQL 文本，参数化管不到列名。
    if (!List.of("status", "transport_mode").contains(column)) {
      throw new IllegalArgumentException("不支持的统计维度：" + column);
    }
    return sql.fetchOne(
            "select count(*) from first_mile_shipment where tenant_id="
                + "nullif(current_setting('app.current_tenant',true),'')::uuid and "
                + column
                + "=?",
            value)
        .get(0, Long.class);
  }

  // ================================================================ 列表

  private static String fromClause() {
    return " from first_mile_shipment r"
        + " join legal_entity_ref le on le.tenant_id=r.tenant_id and le.id=r.legal_entity_id"
        + " join warehouse ow on ow.tenant_id=r.tenant_id and ow.id=r.origin_warehouse_id"
        + " join warehouse dw on dw.tenant_id=r.tenant_id and dw.id=r.destination_warehouse_id";
  }

  /**
   * 每单的聚合：行数、申报货值、计划量、发出量、在途腿数。
   *
   * <p>{@code lateral} 在没有明细行时也返回一行全 0，一张没有明细的发运单不该从列表里「查不到」。 {@code declared_value} 用 coalesce 到 0
   * 而不是留空：申报货值是成本核算的基数， 空值会在前端变成 NaN，而 0 至少是个能被质疑的数。
   */
  private static String aggJoin() {
    return " left join lateral (select count(*) as line_count,"
        + " coalesce(sum(l.declared_value),0) as declared_value,"
        + " coalesce(sum(l.planned_quantity),0) as planned,"
        + " coalesce(sum(l.shipped_quantity),0) as shipped"
        + " from first_mile_shipment_line l"
        + " where l.tenant_id=r.tenant_id and l.shipment_id=r.id) agg on true"
        + " left join lateral (select count(*) as leg_count from first_mile_shipment_leg_receipt g"
        + " where g.tenant_id=r.tenant_id and g.shipment_id=r.id) lg on true";
  }

  private static String rowSelect() {
    return "select r.id as shipment_id,r.shipment_code,r.source_type,r.source_ref_id,"
        + " r.source_ref_code,r.legal_entity_id,le.name as legal_entity_name,"
        + " r.origin_warehouse_id,ow.code as origin_warehouse_code,ow.name as origin_warehouse_name,"
        + " r.destination_warehouse_id,dw.code as destination_warehouse_code,"
        + " dw.name as destination_warehouse_name,r.destination_country_code,r.destination_name,"
        + " r.transport_mode,r.carrier_name,r.booking_no,r.vessel_or_flight_no,"
        + " r.incoterm,r.risk_bearer,r.currency,r.freight_cost,r.insurance_cost,r.duty_estimate,"
        + " r.handling_cost,"
        + " (r.freight_cost+r.insurance_cost+r.duty_estimate+r.handling_cost) as total_cost,"
        + " r.status,r.depart_at,r.expected_arrive_at,r.dispatched_at,r.cancelled_at,"
        + " r.cancel_reason,r.version,"
        + " r.created_at,r.updated_at,"
        + " coalesce(agg.line_count,0) as line_count,coalesce(agg.declared_value,0) as declared_value,"
        + " coalesce(agg.planned,0) as planned_quantity,coalesce(agg.shipped,0) as shipped_quantity,"
        + " coalesce(lg.leg_count,0) as leg_count";
  }

  private void conditions(FirstMileQuery q, String alias, List<String> where, List<Object> binds) {
    String a = alias.isEmpty() ? "" : alias + ".";
    if (q.status() != null && !q.status().isEmpty()) {
      for (var raw : q.status()) {
        where.add(a + "status=?");
        binds.add(ShipmentStatus.parse(raw).name());
      }
    }
    if (q.transportMode() != null && !q.transportMode().isBlank()) {
      where.add(a + "transport_mode=?");
      binds.add(TransportMode.parse(q.transportMode()).name());
    }
    if (q.incoterm() != null && !q.incoterm().isBlank()) {
      where.add(a + "incoterm=?");
      binds.add(Incoterm.parse(q.incoterm()).name());
    }
    if (q.originWarehouseId() != null) {
      where.add(a + "origin_warehouse_id=?");
      binds.add(q.originWarehouseId());
    }
    if (q.destinationWarehouseId() != null) {
      where.add(a + "destination_warehouse_id=?");
      binds.add(q.destinationWarehouseId());
    }
    if (q.destinationCountryCode() != null && !q.destinationCountryCode().isBlank()) {
      where.add("upper(" + a + "destination_country_code)=upper(?)");
      binds.add(q.destinationCountryCode().trim());
    }
    if (Boolean.TRUE.equals(q.inTransitOnly())) {
      // 「在路上」= 已确认待发 或 已发出未了结。与 7.5.1.2 的在途口径同源，
      // 不含已取消与草稿。
      where.add(a + "status in ('CONFIRMED','DISPATCHED')");
    }
    if (q.keyword() != null && !q.keyword().isBlank()) {
      // 真实模糊检索：发运单号、来源单号、订舱号、船名航班号、承运商、目的仓名、备注。
      // **不是**假搜索。
      where.add(
          "("
              + a
              + "shipment_code ilike ? or "
              + a
              + "source_ref_code ilike ? or "
              + a
              + "booking_no ilike ? or "
              + a
              + "vessel_or_flight_no ilike ? or "
              + a
              + "carrier_name ilike ? or "
              + a
              + "destination_name ilike ? or "
              + a
              + "customs_broker ilike ? or "
              + a
              + "notes ilike ?)");
      String kw = "%" + q.keyword().trim() + "%";
      for (int i = 0; i < 8; i++) {
        binds.add(kw);
      }
    }
  }

  /** 排序白名单。**不接受**用户传入的排序表达式 —— 那是 SQL 注入面。 */
  private static String orderBy(FirstMileQuery q) {
    String sort = q.sort() == null ? "UPDATED_AT" : q.sort().toUpperCase(Locale.ROOT);
    if (!SORTS.contains(sort)) {
      sort = "UPDATED_AT";
    }
    return switch (sort) {
      case "CREATED_AT" -> " order by r.created_at desc,r.id";
      case "SHIPMENT_CODE" -> " order by r.shipment_code,r.id";
      case "EXPECTED_ARRIVE_AT" -> " order by r.expected_arrive_at asc nulls last,r.id";
      case "DEPART_AT" -> " order by r.depart_at asc nulls last,r.id";
      default -> " order by r.updated_at desc,r.id";
    };
  }

  /** 列表页的全量汇总：与筛选条件一致，但**不受分页影响**。 */
  private FirstMileSummary summary(FirstMileQuery q) {
    var where = new ArrayList<String>();
    var binds = new ArrayList<Object>();
    conditions(q, "r", where, binds);
    String sqlWhere = where.isEmpty() ? "" : " where " + String.join(" and ", where);
    Record r =
        sql.fetchOne(
            "select count(*) as shipment_count,coalesce(sum(agg.line_count),0) as line_count,"
                + " coalesce(sum(agg.declared_value),0) as declared_value,"
                + " coalesce(sum(r.freight_cost+r.insurance_cost+r.duty_estimate+r.handling_cost),0)"
                + "   as total_cost,"
                + " coalesce(sum(agg.planned),0) as planned,coalesce(sum(agg.shipped),0) as shipped,"
                + " min(r.created_at) as earliest"
                + " from first_mile_shipment r"
                + " left join lateral (select count(*) as line_count,"
                + " coalesce(sum(l.declared_value),0) as declared_value,"
                + " coalesce(sum(l.planned_quantity),0) as planned,"
                + " coalesce(sum(l.shipped_quantity),0) as shipped"
                + " from first_mile_shipment_line l"
                + " where l.tenant_id=r.tenant_id and l.shipment_id=r.id) agg on true"
                + sqlWhere,
            binds.toArray());
    Record c =
        sql.fetchOne(
            "select coalesce(sum(case when r.status='DRAFT' then 1 else 0 end),0) as draft,"
                + " coalesce(sum(case when r.status='CONFIRMED' then 1 else 0 end),0) as confirmed,"
                + " coalesce(sum(case when r.status='DISPATCHED' then 1 else 0 end),0) as dispatched,"
                + " coalesce(sum(case when r.status='CANCELLED' then 1 else 0 end),0) as cancelled,"
                + " coalesce(sum(case when r.status in ('CONFIRMED','DISPATCHED') then 1 else 0 end),0)"
                + "   as in_transit_cnt,"
                + " coalesce(sum(case when r.transport_mode='SEA' then 1 else 0 end),0) as sea,"
                + " coalesce(sum(case when r.transport_mode='AIR' then 1 else 0 end),0) as air,"
                + " coalesce(sum(case when r.transport_mode='RAIL' then 1 else 0 end),0) as rail,"
                + " coalesce(sum(case when r.transport_mode='EXPRESS' then 1 else 0 end),0) as express"
                + " from first_mile_shipment r"
                + sqlWhere,
            binds.toArray());
    return new FirstMileSummary(
        n(r, "shipment_count"),
        n(r, "line_count"),
        decimal(r, "declared_value"),
        decimal(r, "total_cost"),
        decimal(r, "planned"),
        decimal(r, "shipped"),
        n(c, "draft"),
        n(c, "confirmed"),
        n(c, "dispatched"),
        n(c, "cancelled"),
        n(c, "in_transit_cnt"),
        decimal(r, "shipped"),
        n(c, "sea"),
        n(c, "air"),
        n(c, "rail"),
        n(c, "express"),
        r.get("earliest", OffsetDateTime.class));
  }

  public FirstMilePage page(FirstMileQuery q) {
    int size = Math.min(MAX_PAGE_SIZE, Math.max(1, q.size() == null ? 50 : q.size()));
    int pageNumber = Math.max(0, q.page() == null ? 0 : q.page());

    var where = new ArrayList<String>();
    var binds = new ArrayList<Object>();
    conditions(q, "r", where, binds);
    String sqlWhere = where.isEmpty() ? "" : " where " + String.join(" and ", where);

    long total =
        sql.fetchOne("select count(*)" + fromClause() + sqlWhere, binds.toArray())
            .get(0, Long.class);
    var rows =
        sql.fetch(
            rowSelect() + fromClause() + aggJoin() + sqlWhere + orderBy(q) + " limit ? offset ?",
            append(binds, size, (long) pageNumber * size).toArray());

    var caps = capabilities();
    return new FirstMilePage(
        summary(q),
        rows.stream().map(FirstMileShipmentService::row).toList(),
        caps.statusOptions(),
        caps.transportModes(),
        warehouseOptions(),
        total,
        pageNumber,
        size);
  }

  private List<WarehouseOption> warehouseOptions() {
    return sql
        .fetch(
            "select id,code,name,country_code from warehouse where tenant_id="
                + "nullif(current_setting('app.current_tenant',true),'')::uuid"
                + " and status='ACTIVE' order by country_code,code")
        .stream()
        .map(
            r ->
                new WarehouseOption(
                    r.get("id", UUID.class),
                    r.get("code", String.class),
                    r.get("name", String.class),
                    r.get("country_code", String.class)))
        .toList();
  }

  // ================================================================ 详情

  /** 头程发运单详情。跨租户在 RLS 下查不到 → 404（403 会泄露「这张单存在」）。 */
  public FirstMileDetail detail(UUID shipmentId) {
    FirstMileRow shipment = head(shipmentId);
    var status = ShipmentStatus.parse(shipment.status());
    return new FirstMileDetail(
        shipment,
        lineRows(shipmentId),
        transitLegs(shipmentId),
        eventRows(shipmentId),
        ShipmentStatus.allowedNext(status).stream()
            .map(
                to -> new ShipmentTransition(status.name(), to.name(), true, "允许：已确认 → 已发出（登记在途腿）"))
            .toList(),
        capabilities().transitRegistrationNote(),
        capabilities().titleTransferNote());
  }

  private FirstMileRow head(UUID shipmentId) {
    Record r = sql.fetchOne(rowSelect() + fromClause() + aggJoin() + " where r.id=?", shipmentId);
    if (r == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "头程发运单不存在或无权查看");
    }
    return row(r);
  }

  private List<FirstMileLineRow> lineRows(UUID shipmentId) {
    return sql
        .fetch(
            "select l.*,s.code as sku_code,s.name as sku_name,"
                + " q.code as quality_status_code,q.name as quality_status_name,"
                + " lo.lot_code,lo.expires_on as lot_expiry,"
                + " r.receipt_code as source_receipt_code,e.waybill_no as source_waybill_no,"
                + " e.received_at as source_received_at,"
                + " t.transit_leg_id,t2.status as transit_status,"
                + " t2.settled_quantity as transit_settled"
                + " from first_mile_shipment_line l"
                + " join sku_ref s on s.tenant_id=l.tenant_id and s.id=l.sku_id"
                + " join inventory_quality_status q on q.tenant_id=l.tenant_id"
                + "   and q.id=l.quality_status_id"
                + " left join inventory_lot lo on lo.tenant_id=l.tenant_id and lo.id=l.lot_id"
                + " left join goods_receipt_event_line el on el.tenant_id=l.tenant_id"
                + "   and el.id=l.source_receipt_event_line_id"
                + " left join goods_receipt_event e on e.tenant_id=el.tenant_id and e.id=el.event_id"
                + " left join goods_receipt r on r.tenant_id=e.tenant_id and r.id=e.receipt_id"
                + " left join first_mile_shipment_leg_receipt t on t.tenant_id=l.tenant_id"
                + "   and t.line_id=l.id"
                + " left join inventory_transfer t2 on t2.tenant_id=t.tenant_id"
                + "   and t2.id=t.transit_leg_id"
                + " where l.shipment_id=? order by l.line_no",
            shipmentId)
        .stream()
        .map(
            r -> {
              BigDecimal planned = decimal(r, "planned_quantity");
              BigDecimal shipped = decimal(r, "shipped_quantity");
              return new FirstMileLineRow(
                  r.get("id", UUID.class),
                  r.get("line_no", Integer.class),
                  r.get("sku_id", UUID.class),
                  r.get("sku_code", String.class),
                  r.get("sku_name", String.class),
                  r.get("quality_status_id", UUID.class),
                  r.get("quality_status_code", String.class),
                  r.get("quality_status_name", String.class),
                  r.get("source_receipt_event_line_id", UUID.class),
                  r.get("source_receipt_code", String.class),
                  r.get("source_waybill_no", String.class),
                  r.get("source_received_at", OffsetDateTime.class),
                  r.get("lot_id", UUID.class),
                  r.get("lot_code", String.class),
                  r.get("lot_expiry", LocalDate.class),
                  planned,
                  shipped,
                  planned.subtract(shipped).max(BigDecimal.ZERO),
                  r.get("declared_item_name", String.class),
                  r.get("hs_code", String.class),
                  r.get("brand", String.class),
                  r.get("origin_country_code", String.class),
                  decimal(r, "declared_unit_value"),
                  decimal(r, "declared_value"),
                  decimal(r, "gross_weight_kg"),
                  decimal(r, "net_weight_kg"),
                  decimal(r, "volume_cbm"),
                  (int) n(r, "carton_count"),
                  r.get("transit_leg_id", UUID.class),
                  r.get("transit_status", String.class),
                  r.get("transit_settled", BigDecimal.class),
                  n(r, "version"));
            })
        .toList();
  }

  /**
   * 发货分录（在途腿）。
   *
   * <p>左连 {@code inventory_transfer} 读回 7.5.1.2 那一侧的真实在途状态与已了结量： 发运单只记「我登记了哪条腿」，到货了多少是 7.5.1.2
   * 的权威，本模块不另算一份。
   */
  public List<FirstMileTransitLegRow> transitLegs(UUID shipmentId) {
    return sql
        .fetch(
            "select g.*,l.line_no,s.code as sku_code,s.name as sku_name,"
                + " t.transit_kind,t.status as transit_status,t.quantity as transit_quantity,"
                + " t.settled_quantity,t.shipped_at,t.expected_arrive_at,t.carrier_name,"
                + " t.tracking_no"
                + " from first_mile_shipment_leg_receipt g"
                + " join first_mile_shipment_line l on l.tenant_id=g.tenant_id and l.id=g.line_id"
                + " join sku_ref s on s.tenant_id=l.tenant_id and s.id=l.sku_id"
                + " left join inventory_transfer t on t.tenant_id=g.tenant_id"
                + "   and t.id=g.transit_leg_id"
                + " where g.shipment_id=? order by l.line_no",
            shipmentId)
        .stream()
        .map(
            r -> {
              BigDecimal qty = decimal(r, "quantity");
              BigDecimal settled =
                  r.get("settled_quantity", BigDecimal.class) == null
                      ? BigDecimal.ZERO
                      : r.get("settled_quantity", BigDecimal.class);
              return new FirstMileTransitLegRow(
                  r.get("id", UUID.class),
                  r.get("line_id", UUID.class),
                  (int) n(r, "line_no"),
                  r.get("sku_code", String.class),
                  r.get("sku_name", String.class),
                  r.get("transit_leg_id", UUID.class),
                  r.get("transit_kind", String.class),
                  r.get("transit_status", String.class),
                  qty,
                  settled,
                  qty.subtract(settled).max(BigDecimal.ZERO),
                  r.get("shipped_at", OffsetDateTime.class),
                  r.get("expected_arrive_at", OffsetDateTime.class),
                  r.get("carrier_name", String.class),
                  r.get("tracking_no", String.class),
                  r.get("source_system", String.class),
                  r.get("source_reference", String.class),
                  r.get("registered_at", OffsetDateTime.class));
            })
        .toList();
  }

  private List<FirstMileEventRow> eventRows(UUID shipmentId) {
    return sql
        .fetch(
            "select * from first_mile_shipment_event where shipment_id=?"
                + " order by occurred_at desc,id",
            shipmentId)
        .stream()
        .map(
            r ->
                new FirstMileEventRow(
                    r.get("id", UUID.class),
                    r.get("kind", String.class),
                    r.get("occurred_at", OffsetDateTime.class),
                    r.get("from_status", String.class),
                    r.get("to_status", String.class),
                    r.get("actor_id", UUID.class),
                    r.get("reason", String.class),
                    r.get("notes", String.class)))
        .toList();
  }

  // ================================================================ 选货助手

  /**
   * 可发批次候选：8.2.1.1 逐次实收行 + 还能发走多少。
   *
   * <p>头程发运的输入是「选择货物与批次」，而批次挂在**逐次实收**上（同一 SKU 分三车到货 可以是三个批次）。所以候选项精确到实收行而不是精确到 SKU。
   *
   * <p>可发量 = 实收 − 拒收 − 已被其他发运单发走。拒收的那部分从未入库（8.2.1.1 口径）， 算进可发量就是凭空多出一批不存在的货。
   */
  public SourceCandidatePage sourceCandidates(
      UUID receiptId, UUID skuId, UUID warehouseId, String keyword, Integer page, Integer size) {
    int limit = Math.min(MAX_PAGE_SIZE, Math.max(1, size == null ? 50 : size));
    int pageNumber = Math.max(0, page == null ? 0 : page);

    var where = new ArrayList<String>();
    var binds = new ArrayList<Object>();
    if (receiptId != null) {
      where.add("r.id=?");
      binds.add(receiptId);
    }
    if (skuId != null) {
      where.add("gl.sku_id=?");
      binds.add(skuId);
    }
    if (warehouseId != null) {
      where.add("r.warehouse_id=?");
      binds.add(warehouseId);
    }
    if (keyword != null && !keyword.isBlank()) {
      where.add(
          "(r.receipt_code ilike ? or s.code ilike ? or s.name ilike ? or coalesce(lo.lot_code,'') ilike ?)");
      String kw = "%" + keyword.trim() + "%";
      for (int i = 0; i < 4; i++) {
        binds.add(kw);
      }
    }
    // 「还能发走 > 0」必须落在 **WHERE**，不能拼在 lateral 的 ON 后面。
    // ON 子句会一路吃到下一个 AND，写成 `... sh on true and <shippable> > 0` 时
    // 条件不满足只是让 LEFT JOIN 产出 NULL —— 外层行照样保留，于是「已发完的批次」
    // 带着 already_shipped=0 / shippable=100 回到界面上，是一条**假的还能发**记录。
    where.add("el.received_quantity-el.rejected_quantity-coalesce(sh.shipped,0)>0");
    String sqlWhere = " where " + String.join(" and ", where);

    String candidateSql =
        "select el.id as el_id,el.event_id,el.received_quantity,el.rejected_quantity,"
            + " el.lot_id,el.expiry_date,e.receipt_id,e.received_at,e.waybill_no,"
            + " r.receipt_code,r.warehouse_id,gl.sku_id,gl.quality_status_id,"
            + " s.code as sku_code,s.name as sku_name,lo.lot_code,"
            + " q.code as quality_status_code,q.name as quality_status_name,"
            + " w.code as warehouse_code,w.name as warehouse_name,"
            + " coalesce(sh.shipped,0) as already_shipped,"
            + " greatest(el.received_quantity-el.rejected_quantity-coalesce(sh.shipped,0),0)"
            + "   as shippable"
            + " from goods_receipt_event_line el"
            + " join goods_receipt_event e on e.tenant_id=el.tenant_id and e.id=el.event_id"
            + " join goods_receipt r on r.tenant_id=e.tenant_id and r.id=e.receipt_id"
            + " join goods_receipt_line gl on gl.tenant_id=el.tenant_id and gl.id=el.line_id"
            + " join sku_ref s on s.tenant_id=gl.tenant_id and s.id=gl.sku_id"
            + " join inventory_quality_status q on q.tenant_id=gl.tenant_id"
            + "   and q.id=gl.quality_status_id"
            + " join warehouse w on w.tenant_id=r.tenant_id and w.id=r.warehouse_id"
            + " left join inventory_lot lo on lo.tenant_id=el.tenant_id and lo.id=el.lot_id"
            + " left join lateral (select coalesce(sum(l2.shipped_quantity),0) as shipped"
            + "   from first_mile_shipment_line l2"
            + "   join first_mile_shipment h2 on h2.tenant_id=l2.tenant_id"
            + "     and h2.id=l2.shipment_id"
            + "   where l2.tenant_id=el.tenant_id"
            + "     and l2.source_receipt_event_line_id=el.id"
            + "     and h2.status<>'CANCELLED') sh on true"
            + sqlWhere
            // 按可发量少的排：越接近发完的越需要先处理。不写 order by 的话
            // PostgreSQL 的分页会返回任意顺序，翻页时同一批货会时隐时现。
            + " order by shippable asc,el.created_at desc,el.id";

    var rows =
        sql.fetch(
            candidateSql + " limit ? offset ?",
            append(binds, limit, (long) pageNumber * limit).toArray());

    Record totals =
        sql.fetchOne(
            "select count(*) as n,coalesce(sum(shippable),0) as q from (" + candidateSql + ") c",
            binds.toArray());

    return new SourceCandidatePage(
        rows.stream()
            .map(
                r ->
                    new SourceCandidateRow(
                        r.get("el_id", UUID.class),
                        r.get("receipt_id", UUID.class),
                        r.get("receipt_code", String.class),
                        r.get("event_id", UUID.class),
                        r.get("received_at", OffsetDateTime.class),
                        r.get("waybill_no", String.class),
                        r.get("sku_id", UUID.class),
                        r.get("sku_code", String.class),
                        r.get("sku_name", String.class),
                        r.get("quality_status_id", UUID.class),
                        r.get("quality_status_code", String.class),
                        r.get("quality_status_name", String.class),
                        r.get("lot_id", UUID.class),
                        r.get("lot_code", String.class),
                        r.get("expiry_date", LocalDate.class),
                        decimal(r, "received_quantity"),
                        decimal(r, "already_shipped"),
                        decimal(r, "shippable"),
                        r.get("warehouse_id", UUID.class),
                        r.get("warehouse_code", String.class),
                        r.get("warehouse_name", String.class)))
            .toList(),
        decimal(totals, "q"),
        n(totals, "n"),
        pageNumber,
        limit);
  }

  // ================================================================ 建单

  /** 建单（草稿）。用 POST：它真的落库；草稿不动任何账。 */
  @Transactional
  public FirstMileSaved create(UUID tenantId, FirstMileCreateRequest request) {
    var source = ShipmentSourceType.parse(required(request.sourceType(), "货物来源类型"));
    if (!capabilities().sources().stream()
        .filter(s -> s.value().equals(source.name()))
        .findFirst()
        .orElseThrow()
        .available()) {
      // 6 采购未交付：显式拒绝并说明归属模块，而不是建一张没有采购单可指的单。
      throw new IllegalArgumentException(
          "货物来源「"
              + source.label()
              + "」的上游 "
              + source.ownerModule()
              + " 尚未交付，本模块不接受该来源的发运单。"
              + "当前可用来源：GOODS_RECEIPT（8.2.1.1 收货单）。");
    }

    var incoterm = Incoterm.parse(required(request.incoterm(), "贸易术语（成本转嫁方式）"));
    var riskBearer = RiskBearer.parse(required(request.riskBearer(), "风险承担方"));
    requireRiskBearer(incoterm, riskBearer);
    var mode = TransportMode.parse(required(request.transportMode(), "运输方式"));

    UUID legalEntityId = require(request.legalEntityId(), "货权法人");
    requireRef(tenantId, "legal_entity_ref", legalEntityId, "货权法人");
    UUID originId = require(request.originWarehouseId(), "起运仓");
    requireRef(tenantId, "warehouse", originId, "起运仓");
    UUID destinationId = require(request.destinationWarehouseId(), "目的仓");
    requireRef(tenantId, "warehouse", destinationId, "目的仓");
    if (originId.equals(destinationId)) {
      throw new IllegalArgumentException("起运仓与目的仓不能是同一个：头程的定义就是国内到海外，起点终点同仓是录反了");
    }
    String country = required(request.destinationCountryCode(), "目的地国家").toUpperCase(Locale.ROOT);
    if (country.length() != 2) {
      throw new IllegalArgumentException("目的地国家必须是两位国家代码（ISO 3166-1 alpha-2）：" + country);
    }

    // 预计到港是 7.5.1.2 逾期预警的计算依据。发货前不强制必填（还没订舱），
    // 但一旦两个时刻都给了就必须自洽 —— DB 触发器 first_mile_eta 同样钉死这一条。
    OffsetDateTime departAt = request.departAt();
    OffsetDateTime eta = request.expectedArriveAt();
    if (departAt != null && eta != null && eta.isBefore(departAt)) {
      throw new IllegalArgumentException(
          "预计到港 " + eta + " 早于计划开船 " + departAt + "：ETA 是逾期预警的计算依据，录反会全量误报");
    }

    if (source == ShipmentSourceType.GOODS_RECEIPT && request.sourceRefId() == null) {
      throw new IllegalArgumentException(
          "来源为 8.2.1.1 收货单时必须给出 sourceRefId：发运单必须能指回那张收货单，" + "否则「这批货是哪张单收进来的」永远答不出来");
    }
    if (request.lines() == null || request.lines().isEmpty()) {
      throw new IllegalArgumentException("头程发运单至少要有一条明细：没有货物就没有发运");
    }

    UUID id = UUID.randomUUID();
    // 单号 = FM-当天日期-六位序号。序号与日期分两步取，不在一条 SQL 里拼：
    // 纯文本语句里 nextval 套 lpad/::text 这类表达式 jOOQ 解析器吃不准，
    // 拼成一条还会让 PG 报「INSERT has more target columns than expressions」
    // 这种完全指不到真因的错（与 8.2.1.1 收货单同一条纪律）。
    long seq = sql.fetchOne("select nextval('first_mile_shipment_code_seq')").get(0, Long.class);
    var today = sql.fetchOne("select current_date").get(0, LocalDate.class);
    String code = "FM-" + today.toString().replace("-", "") + "-" + String.format("%06d", seq);

    sql.execute(
        "insert into first_mile_shipment(id,tenant_id,shipment_code,source_type,source_ref_id,"
            + "source_ref_code,legal_entity_id,origin_warehouse_id,destination_warehouse_id,"
            + "destination_country_code,destination_name,transport_mode,carrier_name,booking_no,"
            + "vessel_or_flight_no,incoterm,risk_bearer,currency,freight_cost,insurance_cost,"
            + "duty_estimate,handling_cost,customs_broker,status,depart_at,expected_arrive_at,"
            + "notes,created_by) values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'DRAFT',"
            + "?::timestamptz,?::timestamptz,?,?)",
        id,
        tenantId,
        code,
        source.name(),
        source == ShipmentSourceType.GOODS_RECEIPT ? request.sourceRefId() : null,
        fit(request.sourceRefCode(), 64, "来源单号"),
        legalEntityId,
        originId,
        destinationId,
        country,
        fit(request.destinationName(), 200, "目的地名称"),
        mode.name(),
        fit(request.carrierName(), 100, "承运商"),
        fit(request.bookingNo(), 64, "订舱号"),
        fit(request.vesselOrFlightNo(), 64, "船名航班号"),
        incoterm.name(),
        riskBearer.name(),
        currency(request.currency()),
        nonNegative(request.freightCost(), "运费预估"),
        nonNegative(request.insuranceCost(), "保险费预估"),
        nonNegative(request.dutyEstimate(), "关税预估"),
        nonNegative(request.handlingCost(), "操作费预估"),
        fit(request.customsBroker(), 100, "报关行"),
        departAt,
        eta,
        fit(request.notes(), 2000, "备注"),
        UUID.randomUUID());

    insertLines(tenantId, id, source, request.sourceRefId(), request.lines());
    recordEvent(id, "CREATED", "DRAFT", "DRAFT", "", fit(request.notes(), 2000, "备注"));
    return savedOf(id);
  }

  private void insertLines(
      UUID tenantId,
      UUID shipmentId,
      ShipmentSourceType source,
      UUID sourceRefId,
      List<FirstMileLineRequest> lines) {
    var seenSourceLines = new HashMap<String, UUID>();
    int lineNo = 0;
    for (var l : lines) {
      lineNo++;
      UUID skuId = require(l.skuId(), "SKU");
      UUID qualityId = require(l.qualityStatusId(), "质量状态");
      requireRef(tenantId, "inventory_quality_status", qualityId, "质量状态");
      BigDecimal planned = positive(l.plannedQuantity(), "计划发运数量");
      UUID sourceLineId = l.sourceReceiptEventLineId();

      if (source == ShipmentSourceType.GOODS_RECEIPT) {
        if (sourceLineId == null) {
          throw new IllegalArgumentException(
              "来源为收货单时每一行都必须指明是哪一次实收的哪一批货"
                  + "（sourceReceiptEventLineId）：头程发运的输入是「选择货物与批次」，"
                  + "不指批次就无法追这批货的货权与效期");
        }
        // 同一张发运单不允许两行指向同一个实收批次：那是同一批货被拆成两行，
        // 累计发运量会被重复计算成两倍。
        if (seenSourceLines.putIfAbsent(sourceLineId.toString(), sourceLineId) != null) {
          throw new IllegalArgumentException(
              "同一个实收批次 " + sourceLineId + " 在本单出现了多行：" + "同一批货只能有一行，拆行会让累计发运量翻倍");
        }
      }

      // 计划量不得超过该批次还能发走的量（含其他发运单已占用的）。
      BigDecimal shippable = shippableOf(tenantId, sourceLineId, null);
      if (shippable != null && planned.compareTo(shippable) > 0) {
        throw new IllegalArgumentException(
            "计划发运 " + planned + " 超过该实收批次还能发走的量 " + shippable + "（实收 − 拒收 − 已被其他发运单占用）");
      }

      // SKU / 批次必须与那一批实物货对得上。与 DB 触发器 guard_first_mile_line_source
      // 同一条纪律，但放在服务层是为了给出**可读的 400**：只靠触发器的话用户看到的是
      // 一句泛化的 409「数据与既有记录冲突」，完全指不到「SKU 或批次填错了」。
      if (sourceLineId != null) {
        var batch =
            sql.fetchOne(
                "select gl.sku_id as sku_id,el.lot_id as lot_id"
                    + " from goods_receipt_event_line el"
                    + " join goods_receipt_line gl on gl.tenant_id=el.tenant_id"
                    + "   and gl.id=el.line_id"
                    + " where el.tenant_id=? and el.id=?",
                tenantId,
                sourceLineId);
        if (batch == null) {
          throw new IllegalArgumentException("实收批次不存在或不属于本租户：" + sourceLineId);
        }
        if (!batch.get("sku_id", UUID.class).equals(skuId)) {
          throw new IllegalArgumentException(
              "该实收批次属于 SKU "
                  + batch.get("sku_id", UUID.class)
                  + "，与本行填的 SKU "
                  + skuId
                  + " 不是同一个："
                  + "批次与 SKU 对不上会让账实核对永远差一个批次");
        }
        UUID sourceLot = batch.get("lot_id", UUID.class);
        if (l.lotId() != null && !l.lotId().equals(sourceLot)) {
          throw new IllegalArgumentException(
              "本行批次 " + l.lotId() + " 与该实收批次的批次 " + sourceLot + " 不一致");
        }
      }

      sql.execute(
          "insert into first_mile_shipment_line(id,tenant_id,shipment_id,line_no,sku_id,"
              + "quality_status_id,source_receipt_event_line_id,source_receipt_id,lot_id,"
              + "planned_quantity,shipped_quantity,declared_item_name,hs_code,brand,"
              + "origin_country_code,declared_unit_value,declared_value,gross_weight_kg,"
              + "net_weight_kg,volume_cbm,carton_count)"
              // 冗余固化来源收货单：goods_receipt_event_line 自身没有 receipt_id，
              // 收货单在它上游的 goods_receipt_event 上（V70 的三层结构）。
              + " values(?,?,?,?,?,?,?,(select e.receipt_id from goods_receipt_event_line el"
              + "   join goods_receipt_event e on e.tenant_id=el.tenant_id and e.id=el.event_id"
              + "   where el.tenant_id=? and el.id=?),?,?,0,?,?,?,?,?,?,?,?,?,?)",
          UUID.randomUUID(),
          tenantId,
          shipmentId,
          lineNo,
          skuId,
          qualityId,
          sourceLineId,
          tenantId,
          sourceLineId,
          l.lotId(),
          planned,
          // 申报品名必填：10.5.1.2 点名「申报品名不能照搬销售标题」，它是清关口径的独立事实。
          required(l.declaredItemName(), "申报品名"),
          fit(l.hsCode(), 32, "HS 编码"),
          fit(l.brand(), 100, "品牌"),
          required(l.originCountryCode(), "原产国").toUpperCase(Locale.ROOT),
          nonNegative(l.declaredUnitValue(), "申报单价"),
          nonNegative(l.declaredValue(), "申报货值"),
          nonNegative(l.grossWeightKg(), "毛重"),
          nonNegative(l.netWeightKg(), "净重"),
          nonNegative(l.volumeCbm(), "体积"),
          l.cartonCount() == null ? 0 : Math.max(0, l.cartonCount()));
    }
  }

  /**
   * 某实收批次还能发走多少；{@code excludeShipmentId} 非空时把该单的占用量加回来 （改单时本单自己已占的那部分要重算，不能把自己算成别人）。
   *
   * <p>{@code sourceLineId} 为空（供应商来源）时返回 null —— 没有可核的实物批次口径。
   */
  private BigDecimal shippableOf(UUID tenantId, UUID sourceLineId, UUID excludeShipmentId) {
    if (sourceLineId == null) {
      return null;
    }
    Record r =
        sql.fetchOne(
            "select greatest(el.received_quantity-el.rejected_quantity-coalesce((select"
                + " coalesce(sum(l.shipped_quantity),0) from first_mile_shipment_line l"
                + " join first_mile_shipment h on h.tenant_id=l.tenant_id and h.id=l.shipment_id"
                + " where l.tenant_id=el.tenant_id"
                + "   and l.source_receipt_event_line_id=el.id and h.status<>'CANCELLED'"
                + (excludeShipmentId == null ? "" : " and l.shipment_id<>?")
                + "),0),0) as shippable from goods_receipt_event_line el"
                + " where el.tenant_id=? and el.id=?",
            excludeShipmentId == null
                ? new Object[] {tenantId, sourceLineId}
                : new Object[] {excludeShipmentId, tenantId, sourceLineId});
    return r == null ? null : decimal(r, "shippable");
  }

  // ================================================================ 改单

  /** 改单：仅草稿与已确认可改，整份替换明细。已发出之后一行都不让改。 */
  @Transactional
  public FirstMileSaved update(UUID shipmentId, FirstMileUpdateRequest request) {
    Record head = lockHead(shipmentId);
    requireVersion(head, request == null ? null : request.expectedVersion());
    var current = ShipmentStatus.parse(head.get("status", String.class));
    if (!ShipmentStatus.allowedNext(current).contains(ShipmentStatus.DRAFT)) {
      throw statusError(
          current,
          current == ShipmentStatus.DISPATCHED
              ? "已发出是终态：货权已转移、在途腿已在 7.5.1.2 名下，不能改单"
              : "已取消是终态，不能改单");
    }
    UUID tenantId = head.get("tenant_id", UUID.class);
    var source = ShipmentSourceType.parse(head.get("source_type", String.class));
    if (request.lines() == null || request.lines().isEmpty()) {
      throw new IllegalArgumentException("头程发运单至少要有一条明细");
    }

    var incoterm = Incoterm.parse(required(request.incoterm(), "贸易术语（成本转嫁方式）"));
    var riskBearer = RiskBearer.parse(required(request.riskBearer(), "风险承担方"));
    requireRiskBearer(incoterm, riskBearer);
    var mode = TransportMode.parse(required(request.transportMode(), "运输方式"));

    UUID destinationId = require(request.destinationWarehouseId(), "目的仓");
    requireRef(tenantId, "warehouse", destinationId, "目的仓");
    if (head.get("origin_warehouse_id", UUID.class).equals(destinationId)) {
      throw new IllegalArgumentException("起运仓与目的仓不能是同一个");
    }
    String country = required(request.destinationCountryCode(), "目的地国家").toUpperCase(Locale.ROOT);
    if (country.length() != 2) {
      throw new IllegalArgumentException("目的地国家必须是两位国家代码：" + country);
    }
    OffsetDateTime departAt = request.departAt();
    OffsetDateTime eta = request.expectedArriveAt();
    if (departAt != null && eta != null && eta.isBefore(departAt)) {
      throw new IllegalArgumentException("预计到港不能早于计划开船");
    }

    // 整份替换明细：先删后建。已发出是终态，所以这里不可能有在途腿引用要处理。
    sql.execute("delete from first_mile_shipment_line where shipment_id=?", shipmentId);
    insertLines(
        tenantId, shipmentId, source, head.get("source_ref_id", UUID.class), request.lines());

    sql.execute(
        "update first_mile_shipment set source_ref_code=?,destination_warehouse_id=?,"
            + " destination_country_code=?,destination_name=?,transport_mode=?,carrier_name=?,"
            + " booking_no=?,vessel_or_flight_no=?,incoterm=?,risk_bearer=?,currency=?,"
            + " freight_cost=?,insurance_cost=?,duty_estimate=?,handling_cost=?,customs_broker=?,"
            + " depart_at=?::timestamptz,expected_arrive_at=?::timestamptz,notes=?,"
            + " version=version+1,updated_at=now() where id=?",
        fit(request.sourceRefCode(), 64, "来源单号"),
        destinationId,
        country,
        fit(request.destinationName(), 200, "目的地名称"),
        mode.name(),
        fit(request.carrierName(), 100, "承运商"),
        fit(request.bookingNo(), 64, "订舱号"),
        fit(request.vesselOrFlightNo(), 64, "船名航班号"),
        incoterm.name(),
        riskBearer.name(),
        currency(request.currency()),
        nonNegative(request.freightCost(), "运费预估"),
        nonNegative(request.insuranceCost(), "保险费预估"),
        nonNegative(request.dutyEstimate(), "关税预估"),
        nonNegative(request.handlingCost(), "操作费预估"),
        fit(request.customsBroker(), 100, "报关行"),
        departAt,
        eta,
        fit(request.notes(), 2000, "备注"),
        shipmentId);

    recordEvent(
        shipmentId,
        "UPDATED",
        current.name(),
        current.name(),
        "",
        fit(request.notes(), 2000, "备注"));
    return savedOf(shipmentId);
  }

  // ================================================================ 确认

  /** 确认：申报信息齐备、已订舱。货还没走，所以不动任何账。 */
  @Transactional
  public FirstMileSaved confirm(UUID shipmentId, FirstMileConfirmRequest request) {
    Record head = lockHead(shipmentId);
    requireVersion(head, request == null ? null : request.expectedVersion());
    var current = ShipmentStatus.parse(head.get("status", String.class));
    if (!ShipmentStatus.allowedNext(current).contains(ShipmentStatus.CONFIRMED)) {
      throw statusError(current, "当前状态不能确认");
    }
    UUID tenantId = head.get("tenant_id", UUID.class);

    // 确认是「可以发了」的最后一道闸：申报信息不齐就不该放行。
    requireDeclarationReady(shipmentId);

    OffsetDateTime departAt =
        request == null || request.departAt() == null
            ? head.get("depart_at", OffsetDateTime.class)
            : request.departAt();
    OffsetDateTime eta =
        request == null || request.expectedArriveAt() == null
            ? head.get("expected_arrive_at", OffsetDateTime.class)
            : request.expectedArriveAt();
    if (eta == null) {
      throw new IllegalArgumentException(
          "确认前必须给出预计到港时间：它是 7.5.1.2 逾期预警的计算依据，" + "没有 ETA 的在途腿只能显示「未登记预计到达」，预警会立刻变成噪声");
    }
    if (departAt != null && eta.isBefore(departAt)) {
      throw new IllegalArgumentException("预计到港不能早于计划开船");
    }

    sql.execute(
        "update first_mile_shipment set status='CONFIRMED',depart_at=?::timestamptz,"
            + " expected_arrive_at=?::timestamptz,version=version+1,updated_at=now() where id=?",
        departAt,
        eta,
        shipmentId);
    recordEvent(
        shipmentId,
        "CONFIRMED",
        current.name(),
        "CONFIRMED",
        "",
        request == null ? "" : fit(request.notes(), 2000, "确认备注"));
    return savedOf(shipmentId);
  }

  /** 确认前的申报完整性检查。返回第一条不满足的项，报成人话。 */
  private void requireDeclarationReady(UUID shipmentId) {
    for (var r :
        sql.fetch(
            "select line_no,declared_item_name,origin_country_code,hs_code,declared_value,"
                + " gross_weight_kg,carton_count from first_mile_shipment_line"
                + " where shipment_id=? order by line_no",
            shipmentId)) {
      int lineNo = r.get("line_no", Integer.class);
      if (r.get("declared_item_name", String.class) == null
          || r.get("declared_item_name", String.class).isBlank()) {
        throw new IllegalArgumentException("第 " + lineNo + " 行缺申报品名：申报品名是清关口径的必填项");
      }
      String origin = r.get("origin_country_code", String.class);
      if (origin == null || origin.length() != 2) {
        throw new IllegalArgumentException("第 " + lineNo + " 行缺原产国（两位国家代码）");
      }
      if (decimal(r, "declared_value").compareTo(BigDecimal.ZERO) <= 0) {
        throw new IllegalArgumentException(
            "第 " + lineNo + " 行申报货值为 0：货值是关税与 12 财务到岸成本的基数，" + "为 0 会让整条成本链算错");
      }
      if (decimal(r, "gross_weight_kg").compareTo(BigDecimal.ZERO) <= 0) {
        throw new IllegalArgumentException("第 " + lineNo + " 行毛重为 0：海运/空运计费与报关都按毛重算");
      }
      if (n(r, "carton_count") <= 0) {
        throw new IllegalArgumentException("第 " + lineNo + " 行箱数为 0：装箱单与货代提箱按箱数算");
      }
    }
  }

  // ================================================================ 发出

  /**
   * 发出：**货权转移与转在途就在这一刻发生**。
   *
   * <p>顺序是「先登记在途腿，再落发出量与状态」：在途腿登记失败（SKU/批次/仓库校验不过、 或撞 7.5.1.2 的幂等唯一索引）时整个事务回滚，不会留下一张「已发出但货不在路上」的单。
   *
   * <p>{@code lines} 是**逐行发出量的覆盖值**而不是选择集：没列出的行按计划量整行发出。 这样不会出现「发运单已发出、某行发 0」—— 那行会永远留在计划里发不出去
   * （已发出是终态）。部分发运的正确做法是本行发一部分、余量另开一张发运单， DB 触发器 {@code guard_first_mile_over_ship} 按批次累计，不会双发。
   */
  @Transactional
  public FirstMileSaved dispatch(UUID tenantId, UUID shipmentId, FirstMileDispatchRequest request) {
    Record head = lockHead(shipmentId);
    requireVersion(head, request == null ? null : request.expectedVersion());
    var current = ShipmentStatus.parse(head.get("status", String.class));
    if (!ShipmentStatus.allowedNext(current).contains(ShipmentStatus.DISPATCHED)) {
      throw statusError(
          current,
          current == ShipmentStatus.DISPATCHED
              ? "已发出是终态，不能重复发出（在途腿已登记，重复发会双记在途）"
              : "只有已确认的头程发运单才能发出（当前状态：" + current.label() + "）");
    }

    OffsetDateTime departAt = head.get("depart_at", OffsetDateTime.class);
    OffsetDateTime shippedAt =
        request != null && request.shippedAt() != null
            ? request.shippedAt()
            : (departAt != null ? departAt : OffsetDateTime.now());
    OffsetDateTime eta = head.get("expected_arrive_at", OffsetDateTime.class);
    if (eta != null && eta.isBefore(shippedAt)) {
      throw new IllegalArgumentException(
          "预计到港 "
              + eta
              + " 早于发出时间 "
              + shippedAt
              + "："
              + "7.5.1.2 登记在途腿时会直接拒绝这种 ETA，逾期预警也会从第一天起全量误报");
    }

    // 逐行发出量覆盖表：没给的行按计划量。
    var overrides = new HashMap<UUID, BigDecimal>();
    if (request != null && request.lines() != null) {
      for (var d : request.lines()) {
        UUID lineId = require(d.lineId(), "明细行");
        // 这里刻意不要求 > 0：0 与「没填」要走到下面那条领域校验上，才能给出
        // 「已发出是终态，这一行会永远留在计划里发不出去」这种可读原因，
        // 而不是干巴巴一句「实际发出数量必须大于 0」。
        BigDecimal qty = nonNegative(d.shippedQuantity(), "实际发出数量");
        if (overrides.put(lineId, qty) != null) {
          throw new IllegalArgumentException("明细行 " + lineId + " 在发出请求里出现了多次");
        }
      }
    }

    var lines =
        sql.fetch(
            "select id,line_no,sku_id,quality_status_id,source_receipt_event_line_id,lot_id,"
                + " planned_quantity from first_mile_shipment_line where shipment_id=?"
                + " order by line_no for update",
            shipmentId);
    if (lines.isEmpty()) {
      throw new IllegalArgumentException("头程发运单没有明细：没有货物就没有发运");
    }

    String carrier = fit(head.get("carrier_name", String.class), 100, "承运商");
    String tracking = fit(head.get("booking_no", String.class), 100, "订舱号");
    UUID actorId = UUID.randomUUID();
    String shipmentCode = head.get("shipment_code", String.class);
    UUID originWarehouse = head.get("origin_warehouse_id", UUID.class);
    UUID destinationWarehouse = head.get("destination_warehouse_id", UUID.class);
    UUID legalEntity = head.get("legal_entity_id", UUID.class);
    String destinationName = fit(head.get("destination_name", String.class), 200, "目的地名称");
    String originName = warehouseName(tenantId, originWarehouse);
    String destinationWarehouseName = warehouseName(tenantId, destinationWarehouse);
    if (destinationName.isBlank()) {
      destinationName = destinationWarehouseName;
    }

    int dispatchedLines = 0;
    for (var l : lines) {
      UUID lineId = l.get("id", UUID.class);
      int lineNo = l.get("line_no", Integer.class);
      BigDecimal planned = decimal(l, "planned_quantity");
      BigDecimal qty = legQuantity(overrides.getOrDefault(lineId, planned), planned, lineNo);
      if (qty.compareTo(planned) > 0) {
        throw new IllegalArgumentException("第 " + lineNo + " 行实际发出 " + qty + " 超过计划量 " + planned);
      }
      if (qty.signum() <= 0) {
        throw new IllegalArgumentException(
            "第 "
                + lineNo
                + " 行实际发出为 0：已发出是终态，这一行会永远留在计划里发不出去。"
                + "不发这一行请在改单时删掉它；只发一部分请发正数，余量另开一张发运单");
      }
      // 累计口径在 DB 层还有一道延迟约束（guard_first_mile_over_ship）；
      // 这里先给一次可读报错，避免等到提交时才知道。
      BigDecimal shippable =
          shippableOf(tenantId, l.get("source_receipt_event_line_id", UUID.class), null);
      if (shippable != null && qty.compareTo(shippable) > 0) {
        throw new IllegalArgumentException(
            "第 " + lineNo + " 行实际发出 " + qty + " 超过该实收批次还能发走的量 " + shippable + "（同一批货不能发两次）");
      }

      // 先落发出量：在途腿回执的 DB 触发器 guard_first_mile_leg_line 是**即时**触发器，
      // 它拿本行的 shipped_quantity 与回执量对账 —— 先插回执会对着尚未更新的 0 报错。
      // 顺序在同一个事务内，失败整体回滚，所以「先写量还是先登记」对外没有区别。
      sql.execute(
          "update first_mile_shipment_line set shipped_quantity=?,version=version+1,"
              + " updated_at=now() where id=?",
          qty,
          lineId);

      // ===== 唯一的在途登记入口：调 7.5.1.2 的公开端点，绝不直写 inventory_transfer =====
      var savedLeg =
          inTransit.register(
              tenantId,
              new InventoryInTransitModels.InTransitRegisterRequest(
                  InventoryInTransitModels.InTransitKind.FIRST_MILE.name(),
                  l.get("sku_id", UUID.class),
                  l.get("quality_status_id", UUID.class),
                  legalEntity,
                  originWarehouse,
                  originName,
                  destinationWarehouse,
                  destinationWarehouseName,
                  l.get("lot_id", UUID.class),
                  qty,
                  shippedAt,
                  eta,
                  SOURCE_SYSTEM,
                  sourceReference(shipmentCode, lineNo),
                  carrier,
                  tracking));

      sql.execute(
          "insert into first_mile_shipment_leg_receipt(id,tenant_id,shipment_id,line_id,"
              + "transit_leg_id,source_system,source_reference,sku_id,quantity,shipped_at,"
              + "expected_arrive_at,registered_at,registered_by)"
              + " values(?,?,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz,?::timestamptz,?)",
          UUID.randomUUID(),
          tenantId,
          shipmentId,
          lineId,
          savedLeg.id(),
          SOURCE_SYSTEM,
          sourceReference(shipmentCode, lineNo),
          l.get("sku_id", UUID.class),
          qty,
          shippedAt,
          eta,
          OffsetDateTime.now(),
          actorId);

      dispatchedLines++;
    }

    sql.execute(
        "update first_mile_shipment set status='DISPATCHED',dispatched_at=?::timestamptz,"
            + " dispatched_by=?,version=version+1,updated_at=now() where id=?",
        shippedAt,
        actorId,
        shipmentId);
    recordEvent(
        shipmentId,
        "DISPATCHED",
        current.name(),
        "DISPATCHED",
        "",
        (request == null ? "" : fit(request.notes(), 2000, "发出备注"))
            + (dispatchedLines > 0
                ? "（已在 7.5.1.2 登记 " + dispatchedLines + " 条在途腿；" + "本动作不扣在手库存 —— 头程货到货前从未进过我方库存账）"
                : ""));
    return savedOf(shipmentId);
  }

  /**
   * 发运量对齐 7.5.1.2 在途腿的 {@code numeric(19,4)} 口径。
   *
   * <p>计划量沿用 8.2.1.1 的 {@code numeric(19,6)}，但在途腿是 7.5.1.2 建的 {@code numeric(19,4)} 且它的服务层**显式拒绝超过
   * 4 位小数**。所以发出时刻把量 归一到 4 位：能无损归一就归一，归一不掉就报出来 —— 静默四舍五入会让「发出 60.000001 件」 变成在途账上的 60，货权与在途从此对不上。
   */
  private static BigDecimal legQuantity(BigDecimal raw, BigDecimal planned, int lineNo) {
    try {
      return raw.setScale(4, java.math.RoundingMode.UNNECESSARY);
    } catch (ArithmeticException e) {
      throw new IllegalArgumentException(
          "第 "
              + lineNo
              + " 行发出量 "
              + raw
              + " 有超过 4 位小数：7.5.1.2 的在途腿是 numeric(19,4)，"
              + "四舍五入会让在途账上的量与发运单对不上。请按 4 位小数重新下发。");
    }
  }

  /** 幂等锚点：发运单号-行号。与登记进 7.5.1.2 的 sourceReference 完全同值。 */
  private static String sourceReference(String shipmentCode, int lineNo) {
    return shipmentCode + "-" + String.format("%03d", lineNo);
  }

  // ================================================================ 取消

  /** 取消：必须给原因（货权与在途都会因取消而变）。已发出之后不能取消。 */
  @Transactional
  public FirstMileSaved cancel(UUID shipmentId, FirstMileCancelRequest request) {
    Record head = lockHead(shipmentId);
    requireVersion(head, request == null ? null : request.expectedVersion());
    var current = ShipmentStatus.parse(head.get("status", String.class));
    if (!ShipmentStatus.allowedNext(current).contains(ShipmentStatus.CANCELLED)) {
      throw statusError(
          current,
          current == ShipmentStatus.DISPATCHED
              ? "已发出不能取消：货权已转移、在途腿已在 7.5.1.2 名下。" + "要改这批货请走 7.5.1.2 的差异登记"
              : "当前状态不能取消");
    }
    String reason = request == null ? "" : fit(request.reason(), 200, "取消原因");
    if (reason.isBlank()) {
      throw new IllegalArgumentException("取消必须给原因：取消会改变发运计划与货权归属，没有原因事后无法追责");
    }
    sql.execute(
        "update first_mile_shipment set status='CANCELLED',cancelled_at=?::timestamptz,"
            + " cancelled_by=?,cancel_reason=?,version=version+1,updated_at=now() where id=?",
        OffsetDateTime.now(),
        UUID.randomUUID(),
        reason,
        shipmentId);
    recordEvent(
        shipmentId,
        "CANCELLED",
        current.name(),
        "CANCELLED",
        reason,
        request == null ? "" : fit(request.notes(), 2000, "取消备注"));
    return savedOf(shipmentId);
  }

  // ================================================================ 内部

  private Record lockHead(UUID shipmentId) {
    Record r = sql.fetchOne("select * from first_mile_shipment where id=? for update", shipmentId);
    if (r == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "头程发运单不存在或无权查看");
    }
    return r;
  }

  private void recordEvent(
      UUID shipmentId,
      String kind,
      String fromStatus,
      String toStatus,
      String reason,
      String notes) {
    sql.execute(
        "insert into first_mile_shipment_event(id,tenant_id,shipment_id,kind,occurred_at,"
            + "from_status,to_status,actor_id,reason,notes)"
            + " values(?,?,?,?,?::timestamptz,?,?,?,?,?)",
        UUID.randomUUID(),
        currentTenant(),
        shipmentId,
        kind,
        OffsetDateTime.now(),
        fromStatus,
        toStatus,
        UUID.randomUUID(),
        fit(reason, 200, "原因"),
        fit(notes, 2000, "备注"));
  }

  /** 当前 RLS 租户。由 backend/libraries/tenancy 在事务内设置，客户端头改不动它。 */
  private UUID currentTenant() {
    return sql.fetchOne("select nullif(current_setting('app.current_tenant',true),'')::uuid")
        .get(0, UUID.class);
  }

  private void requireRef(UUID tenantId, String table, UUID id, String label) {
    if (id == null
        || sql.fetchOne("select id from " + table + " where tenant_id=? and id=?", tenantId, id)
            == null) {
      throw new IllegalArgumentException(label + "不存在或无权访问：" + id);
    }
  }

  private String warehouseName(UUID tenantId, UUID warehouseId) {
    Record r =
        sql.fetchOne(
            "select name from warehouse where tenant_id=? and id=?", tenantId, warehouseId);
    return r == null ? "" : r.get("name", String.class);
  }

  private void requireRiskBearer(Incoterm incoterm, RiskBearer bearer) {
    if (!allowedRiskBearer(incoterm, bearer)) {
      throw new IllegalArgumentException(
          switch (incoterm) {
            case EXW -> "EXW 工厂交货：风险在卖方场所即转移给买方，风险承担方只能是 BUYER";
            case DDP -> "DDP 完税后交货：卖方负责到目的地，风险承担方只能是 SELLER";
            default -> "贸易术语与风险承担方不自洽";
          });
    }
  }

  /** 乐观锁：版本不符一律 409，绝不静默覆盖。 */
  private void requireVersion(Record head, Long expected) {
    if (expected == null) {
      throw new IllegalArgumentException("缺少 expectedVersion：写操作必须带上乐观锁版本号");
    }
    long actual = head.get("version", Long.class);
    if (actual != expected) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "头程发运单已被他人修改（当前版本 " + actual + "，你的版本 " + expected + "），请刷新后重试");
    }
  }

  private ResponseStatusException statusError(ShipmentStatus current, String detail) {
    return new ResponseStatusException(
        HttpStatus.CONFLICT, detail + "（当前状态：" + current.label() + "）");
  }

  /** 建单/改单/确认/发出/取消后回读：响应是「库里真实存了什么」。 */
  private FirstMileSaved savedOf(UUID shipmentId) {
    var r = head(shipmentId);
    var status = ShipmentStatus.parse(r.status());
    var incoterm = Incoterm.parse(r.incoterm());
    var risk = RiskBearer.parse(r.riskBearer());
    return new FirstMileSaved(
        r.id(),
        r.shipmentCode(),
        status.name(),
        status.label(),
        r.version(),
        r.plannedQuantity(),
        r.shippedQuantity(),
        r.declaredValue(),
        r.totalCost(),
        incoterm.name(),
        incoterm.label(),
        risk.name(),
        risk.label(),
        r.transitLegCount(),
        r.departAt(),
        r.expectedArriveAt(),
        r.dispatchedAt(),
        r.cancelledAt());
  }

  private static FirstMileRow row(Record r) {
    var status = ShipmentStatus.parse(r.get("status", String.class));
    var source = ShipmentSourceType.parse(r.get("source_type", String.class));
    var mode = TransportMode.parse(r.get("transport_mode", String.class));
    var incoterm = Incoterm.parse(r.get("incoterm", String.class));
    var risk = RiskBearer.parse(r.get("risk_bearer", String.class));
    return new FirstMileRow(
        r.get("shipment_id", UUID.class),
        r.get("shipment_code", String.class),
        source.name(),
        source.label(),
        r.get("source_ref_id", UUID.class),
        r.get("source_ref_code", String.class),
        r.get("legal_entity_id", UUID.class),
        r.get("legal_entity_name", String.class),
        r.get("origin_warehouse_id", UUID.class),
        r.get("origin_warehouse_code", String.class),
        r.get("origin_warehouse_name", String.class),
        r.get("destination_warehouse_id", UUID.class),
        r.get("destination_warehouse_code", String.class),
        r.get("destination_warehouse_name", String.class),
        r.get("destination_country_code", String.class),
        r.get("destination_name", String.class),
        mode.name(),
        mode.label(),
        r.get("carrier_name", String.class),
        r.get("booking_no", String.class),
        incoterm.name(),
        incoterm.label(),
        risk.name(),
        risk.label(),
        r.get("currency", String.class),
        decimal(r, "freight_cost"),
        decimal(r, "insurance_cost"),
        decimal(r, "duty_estimate"),
        decimal(r, "handling_cost"),
        decimal(r, "total_cost"),
        decimal(r, "declared_value"),
        decimal(r, "planned_quantity"),
        decimal(r, "shipped_quantity"),
        n(r, "line_count"),
        n(r, "leg_count"),
        status.name(),
        status.label(),
        r.get("depart_at", OffsetDateTime.class),
        r.get("expected_arrive_at", OffsetDateTime.class),
        r.get("dispatched_at", OffsetDateTime.class),
        r.get("cancelled_at", OffsetDateTime.class),
        r.get("cancel_reason", String.class),
        n(r, "version"),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  // ================================================================ 小工具

  /** 读数量。纯文本 SQL 里的表达式列 jOOQ 拿不到列类型，收口成不依赖驱动返回类型的读法。 */
  private static BigDecimal decimal(Record r, String field) {
    var value = r.get(field);
    if (value == null) {
      return BigDecimal.ZERO;
    }
    if (value instanceof BigDecimal d) {
      return d;
    }
    if (value instanceof Number num) {
      return new BigDecimal(num.toString());
    }
    return new BigDecimal(value.toString());
  }

  private static long n(Record r, String field) {
    var value = r.get(field);
    return value == null ? 0L : ((Number) value).longValue();
  }

  private static List<Object> append(List<Object> binds, Object... more) {
    var all = new ArrayList<>(binds);
    all.addAll(java.util.Arrays.asList(more));
    return all;
  }

  private static String required(String value, String label) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("缺少必填项：" + label);
    }
    return value.trim();
  }

  private static UUID require(UUID value, String label) {
    if (value == null) {
      throw new IllegalArgumentException("缺少必填项：" + label);
    }
    return value;
  }

  private static BigDecimal positive(BigDecimal value, String label) {
    if (value == null || value.signum() <= 0) {
      throw new IllegalArgumentException(label + "必须大于 0");
    }
    return value;
  }

  private static BigDecimal nonNegative(BigDecimal value, String label) {
    if (value == null) {
      return BigDecimal.ZERO;
    }
    if (value.signum() < 0) {
      throw new IllegalArgumentException(label + "不能为负数");
    }
    return value;
  }

  /** 币种：三位大写字母。空时给 USD 并在响应里如实返回，不静默换算。 */
  private static String currency(String raw) {
    if (raw == null || raw.isBlank()) {
      return "USD";
    }
    String code = raw.trim().toUpperCase(Locale.ROOT);
    if (!code.matches("[A-Z]{3}")) {
      throw new IllegalArgumentException("币种必须是三位大写字母代码（如 USD / CNY）：" + raw);
    }
    return code;
  }

  /** 文本截断到列宽。超长直接报错而不是静默截断 —— 静默截断会让单号与申报品名对不上。 */
  private static String fit(String value, int max, String label) {
    if (value == null) {
      return "";
    }
    String v = value.trim();
    if (v.length() > max) {
      throw new IllegalArgumentException(label + "超过 " + max + " 字符（当前 " + v.length() + "）");
    }
    return v;
  }
}
