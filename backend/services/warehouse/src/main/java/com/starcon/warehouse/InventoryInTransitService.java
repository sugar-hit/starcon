package com.starcon.warehouse;

import static com.starcon.warehouse.InventoryInTransitModels.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 7.5.1.2 在途库存。
 *
 * <p>蓝图：「跟踪所有在途库存（调拨在途、头程在途、采购在途、FBA 发货在途），
 * 含起止仓、批次、数量、预计到达」；输出「在途库存清单 + 到达预警（逾期未达）」。
 *
 * <p>六条实现纪律：
 *
 * <ol>
 *   <li><b>只有一张在途事实表</b>。V1 的 {@code inventory_transfer} 就是全服务在途的唯一出处
 *       （7.1.1.1 / 7.2.2.1 / 7.1.3.1 / 7.5.1.1 都按它求和），本模块只做**扩列**不做新表。
 *       蓝图关系段说「在途漏算会导致重复补货」——漏算的根因永远是同一个在途有两套账，
 *       所以头程/采购/FBA 也写进这张表，既有四个读者一行都不用改就自动算进新在途。
 *   <li><b>调拨腿只读</b>。调拨在途由 7.5.1.1 发出时产生，本模块的登记端点显式拒绝
 *       {@code TRANSFER}，否则两处都能写同一批货，在途数会翻倍。
 *   <li><b>逾期是真算的</b>：{@link InTransitOverdueLevel#of} 与 SQL 里的
 *       {@link #overdueLevel(String)} 用同一套阈值，ETA 来自库里的真实时刻，
 *       库里没有任何「已逾期」标记列可被人随手写上。
 *   <li><b>ETA 对调拨腿从调拨单取</b>（{@code coalesce(t.expected_arrive_at, transfer_order.expected_arrive_at)}），
 *       所以 7.5.1.1 既有的单据立刻有 ETA，<b>不需要改它的一行代码</b>。
 *   <li><b>外部腿的落账分两段</b>：FBA 发货登记时就把货从我方仓扣掉（货真的离仓了，
 *       不扣就是虚增在手）；头程/采购登记时不动库存（货还没进我方账），
 *       到达时才入库 —— 否则会在货到之前凭空多出库存。
 *   <li><b>跨服务只带定位键</b>：{@code source_system + source_reference}，
 *       不连 10 物流 / 6 采购 / 4 外部仓的库（它们都未交付），见 V63 文件头第二节。
 * </ol>
 *
 * <p>状态机只有一处真相：{@link #settle}。到达与差异登记都改完数量后由它统一收尾，
 * 「到达最后一点该是已收还是该还挂着在途」这种边界不写在两个地方。
 */
@Service
public class InventoryInTransitService {

  /** 单页最大行数。 */
  static final int MAX_PAGE_SIZE = 200;

  private static final List<String> SORTS =
      List.of("EXPECTED_ARRIVE", "SHIPPED_AT", "QUANTITY", "SKU_CODE", "OVERDUE", "CREATED_AT");

  /** 补货净额口径说明，随响应返回：下游拿去扣缺口，但本模块不给补货建议。 */
  private static final String NETTING_NOTE =
      "按「目的仓 × SKU」聚合的未达在途量，6.2.1.1 算缺口时应从这里扣减，"
          + "不要再单独查一遍在途（两套口径迟早会给出两个数）。"
          + "noEtaQuantity 是其中没有登记预计到达的部分：货确实在路上，但排期未知，"
          + "所以扣减量包含它，而排期类判断不能假设它会按时到。";

  private final DSLContext sql;

  public InventoryInTransitService(DSLContext sql) {
    this.sql = sql;
  }

  // ================================================================ SQL 片段

  /**
   * 预计到达的解析式。
   *
   * <p>调拨腿的 ETA 落在 {@code transfer_order} 上（7.5.1.1 的单头字段），
   * 在途腿自身没有这一列也照样能算出逾期 —— 这是本模块**不侵入 7.5.1.1** 的关键：
   * 读侧派生，而不是去改别人的写侧。
   */
  private static String eta(String a) {
    return "coalesce(t" + a + ".expected_arrive_at,tor" + a + ".expected_arrive_at)";
  }

  /**
   * 逾期档位的 SQL 表达式 —— 与 {@link InTransitOverdueLevel#of} 同一套阈值。
   *
   * <p>用 {@code now()} 而不是绑定参数：档位是**查询时刻**的事实，
   * 绑定一个时刻进来反而会让「列表的逾期档」和「详情/预警的逾期档」在两个时刻各算一次，
   * 用户会看到同一条腿在两个页面上档位不一致。
   *
   * <p>顺序不可交换：先判「已了结」与「无 ETA」，剩下的才按小时数落桶。
   */
  private static String overdueLevel(String a) {
    String e = eta(a);
    return "case when t"
        + a
        + ".status<>'IN_TRANSIT' or t"
        + a
        + ".quantity<=0 then 'ON_TIME' when "
        + e
        + " is null then 'NO_ETA' when "
        + e
        + " > now() then 'ON_TIME' when "
        + e
        + " > now() - interval '24 hours' then 'MILD' when "
        + e
        + " > now() - interval '72 hours' then 'OVERDUE' when "
        + e
        + " > now() - interval '168 hours' then 'SEVERE' else 'CRITICAL' end";
  }

  /**
   * 在途腿的连接形状。
   *
   * <p>warehouse / inventory_lot / transfer_order 全部 **left join**：
   * 头程的起点、FBA 的终点不在本租户仓内，transfer_order 只对调拨腿存在。
   * 用 inner join 会把这类腿**整条静默丢掉** —— 那正是「在途漏算」最隐蔽的一种。
   */
  private static String legFrom(String a) {
    return " from inventory_transfer t"
        + a
        + " join sku_ref s"
        + a
        + " on s.tenant_id=t"
        + a
        + ".tenant_id and s.id=t"
        + a
        + ".sku_id"
        + " join inventory_quality_status q"
        + a
        + " on q.tenant_id=t"
        + a
        + ".tenant_id and q.id=t"
        + a
        + ".quality_status_id"
        + " join legal_entity_ref le"
        + a
        + " on le.tenant_id=t"
        + a
        + ".tenant_id and le.id=t"
        + a
        + ".legal_entity_id"
        + " left join warehouse fw"
        + a
        + " on fw.tenant_id=t"
        + a
        + ".tenant_id and fw.id=t"
        + a
        + ".from_warehouse_id"
        + " left join warehouse tw"
        + a
        + " on tw.tenant_id=t"
        + a
        + ".tenant_id and tw.id=t"
        + a
        + ".to_warehouse_id"
        + " left join inventory_lot lt"
        + a
        + " on lt.tenant_id=t"
        + a
        + ".tenant_id and lt.id=t"
        + a
        + ".lot_id"
        + " left join transfer_order_line tol"
        + a
        + " on tol.tenant_id=t"
        + a
        + ".tenant_id and tol.id=t"
        + a
        + ".line_id"
        + " left join transfer_order tor"
        + a
        + " on tor.tenant_id=t"
        + a
        + ".tenant_id and tor.id=tol"
        + a
        + ".order_id";
  }

  private static String legSelect(String a) {
    return "select t"
        + a
        + ".id,t"
        + a
        + ".transit_kind,t"
        + a
        + ".sku_id,s.code as sku_code,s.name as sku_name,t"
        + a
        + ".quality_status_id,q.code as quality_status_code,t"
        + a
        + ".legal_entity_id,le.code as legal_entity_code,t"
        + a
        + ".from_warehouse_id,fw.code as from_warehouse_code,t"
        + a
        + ".to_warehouse_id,tw.code as to_warehouse_code,t"
        + a
        + ".lot_id,lt.lot_code,t"
        + a
        + ".quantity,t"
        + a
        + ".settled_quantity,t"
        + a
        + ".shipped_at,t"
        + a
        + ".received_at,t"
        + a
        + ".status,t"
        + a
        + ".carrier_name,t"
        + a
        + ".tracking_no,t"
        + a
        + ".source_system,t"
        + a
        + ".source_reference,t"
        + a
        + ".from_warehouse_name,t"
        + a
        + ".to_warehouse_name,t"
        + a
        + ".version,tor.id as transfer_order_id,tor.order_code as transfer_order_code,"
        + eta(a)
        + " as expected_arrive_at,"
        + overdueLevel(a)
        + " as overdue_level ";
  }

  // ================================================================ 清单

  /** 在途清单 + 全量汇总 + 下拉词表。 */
  public InTransitPage page(InTransitQuery query) {
    int size = Math.min(MAX_PAGE_SIZE, Math.max(1, query.size() == null ? 50 : query.size()));
    int pageNumber = Math.max(0, query.page() == null ? 0 : query.page());

    var where = new ArrayList<String>();
    var binds = new ArrayList<Object>();
    conditions(query, "", where, binds);
    String sqlWhere = where.isEmpty() ? "" : " where " + String.join(" and ", where);

    long total =
        sql.fetchOne("select count(*)" + legFrom("") + sqlWhere, binds.toArray()).get(0, Long.class);

    var rows =
        sql.fetch(
            legSelect("") + legFrom("") + sqlWhere + order(query) + " limit ? offset ?",
            append(binds, size, (long) pageNumber * size).toArray());

    return new InTransitPage(
        summary(query),
        rows.stream().map(r -> row(r, OffsetDateTime.now())).toList(),
        kindOptions(),
        overdueOptions(),
        warehouseOptions(),
        total,
        pageNumber,
        size);
  }

  /** 清单汇总。**全量**，不受分页影响。 */
  private InTransitSummary summary(InTransitQuery query) {
    var where = new ArrayList<String>();
    var binds = new ArrayList<Object>();
    conditions(query, "", where, binds);
    String sqlWhere = where.isEmpty() ? "" : " where " + String.join(" and ", where);

    Record r =
        sql.fetchOne(
            "select count(*) as leg_count,"
                + " coalesce(sum(t.quantity),0) as outstanding,"
                + " count(*) filter (where t.transit_kind='TRANSFER') as transfer_count,"
                + " coalesce(sum(t.quantity) filter (where t.transit_kind='TRANSFER'),0) as transfer_qty,"
                + " count(*) filter (where t.transit_kind='FIRST_MILE') as first_mile_count,"
                + " coalesce(sum(t.quantity) filter (where t.transit_kind='FIRST_MILE'),0) as first_mile_qty,"
                + " count(*) filter (where t.transit_kind='PURCHASE') as purchase_count,"
                + " coalesce(sum(t.quantity) filter (where t.transit_kind='PURCHASE'),0) as purchase_qty,"
                + " count(*) filter (where t.transit_kind='FBA_OUTBOUND') as fba_count,"
                + " coalesce(sum(t.quantity) filter (where t.transit_kind='FBA_OUTBOUND'),0) as fba_qty,"
                + " count(*) filter (where "
                + overdueLevel("")
                + " in ('MILD','OVERDUE','SEVERE','CRITICAL')) as overdue_count,"
                + " coalesce(sum(t.quantity) filter (where "
                + overdueLevel("")
                + " in ('MILD','OVERDUE','SEVERE','CRITICAL')),0) as overdue_qty,"
                + " count(*) filter (where "
                + overdueLevel("")
                + "='NO_ETA') as no_eta_count,"
                + " min("
                + eta("")
                + ") as earliest_expected_arrive"
                + legFrom("")
                + sqlWhere,
            binds.toArray());

    return new InTransitSummary(
        r.get("leg_count", Long.class),
        decimal(r, "outstanding"),
        decimal(r, "transfer_qty"),
        r.get("first_mile_count", Long.class),
        decimal(r, "first_mile_qty"),
        r.get("purchase_count", Long.class),
        decimal(r, "purchase_qty"),
        r.get("fba_count", Long.class),
        decimal(r, "fba_qty"),
        r.get("overdue_count", Long.class),
        decimal(r, "overdue_qty"),
        r.get("no_eta_count", Long.class),
        r.get("earliest_expected_arrive", OffsetDateTime.class));
  }

  /**
   * 类型下拉：四类全列（含当前 0 条的），带真实腿数。
   *
   * <p>「头程在途 = 0 条」本身就是有用的信息：说明这个租户还没接过头程发运事件，
   * 而不是这个功能不存在。
   */
  private List<InTransitOption> kindOptions() {
    var counts = new java.util.HashMap<String, Long>();
    for (var row :
        sql.fetch(
            "select transit_kind,count(*) as c from inventory_transfer"
                + " where status='IN_TRANSIT' group by transit_kind")) {
      counts.put(row.get("transit_kind", String.class), row.get("c", Long.class));
    }
    var out = new ArrayList<InTransitOption>();
    for (var k : InTransitKind.values()) {
      out.add(new InTransitOption(k.name(), k.label(), counts.getOrDefault(k.name(), 0L)));
    }
    return out;
  }

  /** 逾期档位下拉：各档当前条数，界面直接显示「有多少条要处理」。 */
  private List<InTransitOption> overdueOptions() {
    var counts = new java.util.HashMap<String, Long>();
    var rows =
        sql.fetch(
            "select " + overdueLevel("") + " as lvl,count(*) as c"
                + legFrom("")
                + " where t.status='IN_TRANSIT' group by lvl");
    for (var row : rows) {
      counts.put(row.get("lvl", String.class), row.get("c", Long.class));
    }
    var out = new ArrayList<InTransitOption>();
    for (var l : InTransitOverdueLevel.values()) {
      out.add(new InTransitOption(l.name(), l.label(), counts.getOrDefault(l.name(), 0L)));
    }
    return out;
  }

  /** 仓库下拉：起止仓共用同一份清单。 */
  private List<InTransitWarehouseOption> warehouseOptions() {
    return sql.fetch(
            "select w.id,w.code,w.name from warehouse w where w.status<>'ARCHIVED' order by w.code")
        .map(r -> new InTransitWarehouseOption(
            r.get("id", UUID.class), r.get("code", String.class), r.get("name", String.class)));
  }

  private void conditions(InTransitQuery q, String a, List<String> where, List<Object> binds) {
    // 清单只列**仍在途**的腿：已了结的腿答不出「还在不在路上」，它属于历史而不是在途，
    // 要看某一腿的到达经过请走详情端点（legId 可从调拨单或预警时间线拿到）。
    // 这也让下拉的各档条数与汇总口径一致，不用解释「为什么列表 3 条但汇总 5 条」。
    where.add("t" + a + ".status='IN_TRANSIT'");
    if (q.transitKind() != null && !q.transitKind().isBlank()) {
      where.add("t" + a + ".transit_kind=?");
      binds.add(InTransitKind.parse(q.transitKind().trim()).name());
    }
    if (q.fromWarehouseId() != null) {
      where.add("t" + a + ".from_warehouse_id=?");
      binds.add(q.fromWarehouseId());
    }
    if (q.toWarehouseId() != null) {
      where.add("t" + a + ".to_warehouse_id=?");
      binds.add(q.toWarehouseId());
    }
    if (q.skuId() != null) {
      where.add("t" + a + ".sku_id=?");
      binds.add(q.skuId());
    }
    if (q.overdueLevel() != null && !q.overdueLevel().isEmpty()) {
      var levels = new ArrayList<String>();
      for (var raw : q.overdueLevel()) {
        if (raw == null || raw.isBlank()) {
          continue;
        }
        if (!InTransitOverdueLevel.names().contains(raw.trim().toUpperCase(Locale.ROOT))) {
          throw new IllegalArgumentException(
              "未知的逾期档位："
                  + raw
                  + "，可选："
                  + String.join("、", InTransitOverdueLevel.names()));
        }
        levels.add(raw.trim().toUpperCase(Locale.ROOT));
      }
      if (!levels.isEmpty()) {
        where.add("(" + overdueLevel(a) + ") in (" + placeholders(levels.size()) + ")");
        binds.addAll(levels);
      }
    }
    if (Boolean.TRUE.equals(q.overdueOnly())) {
      where.add("(" + overdueLevel(a) + ") in ('MILD','OVERDUE','SEVERE','CRITICAL')");
    }
    if (q.keyword() != null && !q.keyword().isBlank()) {
      var k = "%" + q.keyword().trim() + "%";
      where.add(
          "(s"
              + a
              + ".code ilike ? or s"
              + a
              + ".name ilike ? or t"
              + a
              + ".tracking_no ilike ? or t"
              + a
              + ".carrier_name ilike ? or t"
              + a
              + ".source_reference ilike ? or t"
              + a
              + ".from_warehouse_name ilike ? or t"
              + a
              + ".to_warehouse_name ilike ? or tor"
              + a
              + ".order_code ilike ?)");
      for (int i = 0; i < 8; i++) {
        binds.add(k);
      }
    }
  }

  /** 排序。决胜键恒定追加 {@code t.id}，避免同值翻页错位。 */
  private String order(InTransitQuery q) {
    String sort = q.sort() == null ? "EXPECTED_ARRIVE" : q.sort().toUpperCase(Locale.ROOT);
    if (!SORTS.contains(sort)) {
      throw new IllegalArgumentException(
          "不支持的排序键：" + q.sort() + "，可选：" + String.join("、", SORTS));
    }
    return switch (sort) {
      case "SHIPPED_AT" -> " order by t.shipped_at desc,t.id";
      case "QUANTITY" -> " order by t.quantity desc,t.id";
      case "SKU_CODE" -> " order by s.code,t.id";
      case "CREATED_AT" -> " order by t.created_at desc,t.id";
      case "OVERDUE" ->
          " order by ("
              + overdueLevel("")
              + ") in ('CRITICAL','SEVERE','OVERDUE','MILD','NO_ETA') desc,"
              + eta("")
              + " asc nulls last,t.id";
      default -> " order by " + eta("") + " asc nulls last,t.id";
    };
  }

  // ================================================================ 详情

  /**
   * 在途腿详情：行 + 该腿的到达/差异时间线。
   *
   * <p>跨租户在 RLS 下查不到 → 404（403 会泄露「这批货存在」）。
   */
  public InTransitDetail detail(UUID legId) {
    Record r = sql.fetchOne(legSelect("") + legFrom("") + " where t.id=?", legId);
    if (r == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "在途记录不存在或无权查看");
    }
    return new InTransitDetail(row(r, OffsetDateTime.now()), events(legId), metricNote(r));
  }

  /** 到达/差异时间线：按发生顺序读出这一腿的完整经过。 */
  public List<InTransitEventRow> events(UUID legId) {
    return sql.fetch(
            "select e.id,e.kind,e.occurred_at,e.quantity,e.location_id,loc.code as location_code,"
                + "e.lot_id,l.lot_code,e.reason,e.notes"
                + " from in_transit_event e"
                + " left join warehouse_location loc"
                + " on loc.tenant_id=e.tenant_id and loc.id=e.location_id"
                + " left join inventory_lot l on l.tenant_id=e.tenant_id and l.id=e.lot_id"
                + " where e.tenant_id=? and e.leg_id=? order by e.occurred_at,e.id",
            sql.fetchOne("select tenant_id from inventory_transfer where id=?", legId)
                .get("tenant_id", UUID.class),
            legId)
        .map(
            e ->
                new InTransitEventRow(
                    e.get("id", UUID.class),
                    e.get("kind", String.class),
                    e.get("occurred_at", OffsetDateTime.class),
                    decimal(e, "quantity"),
                    e.get("location_id", UUID.class),
                    e.get("location_code", String.class),
                    e.get("lot_id", UUID.class),
                    e.get("lot_code", String.class),
                    e.get("reason", String.class),
                    e.get("notes", String.class)));
  }

  // ================================================================ 预警

  /**
   * 到达预警（逾期未达）。
   *
   * <p>完全由「ETA + 此刻 + 仍在途」算出，库里没有可写的逾期标记列：
   * ETA 改了、货到了，预警立刻跟着变，不存在「忘了取消预警」这种状态。
   */
  public InTransitAlerts alerts() {
    OffsetDateTime now = OffsetDateTime.now();
    var rows =
        sql.fetch(
            legSelect("")
                + legFrom("")
                + " where t.status='IN_TRANSIT' and ("
                + overdueLevel("")
                + ") in ('MILD','OVERDUE','SEVERE','CRITICAL')"
                + " order by "
                + eta("")
                + " asc nulls last,t.id");
    var list = rows.stream().map(r -> row(r, now)).toList();
    BigDecimal qty =
        list.stream()
            .map(InTransitRow::outstandingQuantity)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    return new InTransitAlerts(
        now, list.size(), qty, list, overdueOptions());
  }

  // ================================================================ 补货净额

  /**
   * 补货可扣在途净额（按目的仓 × SKU）。
   *
   * <p>只给数不给建议：需求预测与下单属 6.2.1.1，本模块不越界替它做决定。
   * 目的地不在本租户仓内的腿（FBA 发往平台仓）**不进这个净额** ——
   * 平台仓的在途不构成本仓的入库缺口，混进去反而会让补货算错方向。
   */
  public InTransitNettingPage netting(UUID toWarehouseId, UUID skuId, String sort, int page, int size) {
    int pageSize = Math.min(MAX_PAGE_SIZE, Math.max(1, size <= 0 ? 50 : size));
    int pageNumber = Math.max(0, page);
    var where = new ArrayList<String>();
    var binds = new ArrayList<Object>();
    where.add("t.status='IN_TRANSIT'");
    where.add("t.to_warehouse_id is not null");
    if (toWarehouseId != null) {
      where.add("t.to_warehouse_id=?");
      binds.add(toWarehouseId);
    }
    if (skuId != null) {
      where.add("t.sku_id=?");
      binds.add(skuId);
    }
    String sqlWhere = " where " + String.join(" and ", where);

    long total =
        sql.fetchOne(
                "select count(*) from (select t.to_warehouse_id,t.sku_id"
                    + legFrom("")
                    + sqlWhere
                    + " group by t.to_warehouse_id,t.sku_id) g",
                binds.toArray())
            .get(0, Long.class);

    String orderBy =
        switch (sort == null ? "QUANTITY" : sort.toUpperCase(Locale.ROOT)) {
          case "LATEST_ARRIVAL" -> " order by g.latest_expected_arrive desc nulls last,g.sku_id";
          case "SKU_CODE" -> " order by g.sku_code,g.sku_id";
          default -> " order by g.outstanding desc,g.sku_id";
        };

    var rows =
        sql.fetch(
            "select g.to_warehouse_id,w.code as to_warehouse_code,w.name as to_warehouse_name,"
                + "g.sku_id,s.code as sku_code,s.name as sku_name,"
                + "g.outstanding,g.no_eta,g.leg_count,g.latest_expected_arrive"
                + " from (select t.to_warehouse_id,t.sku_id,"
                + " sum(t.quantity) as outstanding,"
                + " sum(t.quantity) filter (where "
                + eta("")
                + " is null) as no_eta,"
                + " count(*) as leg_count,"
                + " max("
                + eta("")
                + ") as latest_expected_arrive"
                + legFrom("")
                + sqlWhere
                + " group by t.to_warehouse_id,t.sku_id) g"
                + " join warehouse w on w.tenant_id=? and w.id=g.to_warehouse_id"
                + " join sku_ref s on s.tenant_id=? and s.id=g.sku_id"
                + orderBy
                + " limit ? offset ?",
            append(binds, tenant(), tenant(), pageSize, (long) pageNumber * pageSize).toArray());

    List<InTransitNettingRow> list =
        rows.stream()
            .map(
                r ->
                    new InTransitNettingRow(
                        r.get("to_warehouse_id", UUID.class),
                        r.get("to_warehouse_code", String.class),
                        r.get("to_warehouse_name", String.class),
                        r.get("sku_id", UUID.class),
                        r.get("sku_code", String.class),
                        r.get("sku_name", String.class),
                        decimal(r, "outstanding"),
                        decimal(r, "no_eta"),
                        r.get("leg_count", Long.class),
                        r.get("latest_expected_arrive", OffsetDateTime.class)))
            .toList();

    BigDecimal totalQty =
        list.stream()
            .map(InTransitNettingRow::outstandingQuantity)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    return new InTransitNettingPage(total, totalQty, list, NETTING_NOTE);
  }

  // ================================================================ 登记（头程 / 采购 / FBA 发运事件）

  /**
   * 登记外部在途腿。
   *
   * <p>调拨腿（{@code TRANSFER}）显式拒绝：调拨在途由 7.5.1.1 调拨单发出时产生，
   * 两处都能写同一批货会让在途数翻倍 —— 那正是本模块要防的漏算/重算。
   *
   * <p>幂等：{@code sourceSystem + sourceReference} 上有部分唯一索引，
   * 上游重复投递同一条发运事件时撞唯一约束（409），不会双记在途。
   */
  public InTransitSaved register(UUID tenantId, InTransitRegisterRequest request) {
    InTransitKind kind = InTransitKind.parse(required(request.transitKind(), "在途类型"));
    if (kind == InTransitKind.TRANSFER) {
      throw new IllegalArgumentException(
          "调拨在途由 7.5.1.1 调拨单的发出动作产生，不能在这里登记（否则在途会被记两次）");
    }
    String sourceSystem = required(request.sourceSystem(), "来源系统");
    String sourceReference = required(request.sourceReference(), "来源单据号");

    UUID skuId = request.skuId();
    if (skuId == null
        || sql.fetchOne("select id from sku_ref where tenant_id=? and id=?", tenantId, skuId) == null) {
      throw new IllegalArgumentException("SKU 未在本租户登记：" + skuId);
    }
    UUID quality = request.qualityStatusId();
    if (quality == null
        || sql.fetchOne(
                "select id from inventory_quality_status where tenant_id=? and id=?",
                tenantId, quality)
            == null) {
      throw new IllegalArgumentException("质量状态不存在或无权访问：" + quality);
    }
    UUID legalEntity = request.legalEntityId();
    if (legalEntity == null
        || sql.fetchOne(
                "select id from legal_entity_ref where tenant_id=? and id=?", tenantId, legalEntity)
            == null) {
      throw new IllegalArgumentException("货权法人不存在或无权访问：" + legalEntity);
    }

    UUID lotId = request.lotId();
    if (lotId != null
        && sql.fetchOne(
                "select id from inventory_lot where tenant_id=? and id=? and sku_id=?",
                tenantId, lotId, skuId)
            == null) {
      throw new IllegalArgumentException("批次不存在、不属于该 SKU 或无权访问：" + lotId);
    }

    // 起止站：进我方仓的类型必须有目的仓，发往平台仓的类型必须有源仓。
    String fromName = fit(request.fromWarehouseName(), 200, "起点名称");
    String toName = fit(request.toWarehouseName(), 200, "终点名称");
    if (request.fromWarehouseId() == null && fromName.isBlank()) {
      throw new IllegalArgumentException(
          "起点不是本租户的仓库时必须给出可读名称（供应商工厂、平台仓等不在我们系统里）");
    }
    if (request.toWarehouseId() == null && toName.isBlank()) {
      throw new IllegalArgumentException(
          "终点不是本租户的仓库时必须给出可读名称（供应商工厂、平台仓等不在我们系统里）");
    }
    if (kind == InTransitKind.FBA_OUTBOUND && request.fromWarehouseId() == null) {
      throw new IllegalArgumentException("FBA 发货在途必须指定我方发货仓（登记时要从该仓扣减在手）");
    }
    if (kind != InTransitKind.FBA_OUTBOUND && request.toWarehouseId() == null) {
      throw new IllegalArgumentException(
          kind.label() + "必须指定我方收货仓（货最终要进这个仓，缺了它就没有入库落点）");
    }
    if (request.fromWarehouseId() != null
        && request.toWarehouseId() != null
        && request.fromWarehouseId().equals(request.toWarehouseId())) {
      throw new IllegalArgumentException("起点仓与终点仓不能是同一个仓");
    }

    BigDecimal qty = positive(request.quantity(), "在途数量");
    OffsetDateTime shippedAt =
        request.shippedAt() == null ? OffsetDateTime.now() : request.shippedAt();
    OffsetDateTime eta = request.expectedArriveAt();
    if (eta != null && eta.isBefore(shippedAt)) {
      throw new IllegalArgumentException("预计到达时间不能早于发出时间");
    }

    UUID legId = UUID.randomUUID();

    // FBA 发货：货在这一刻真的离仓，所以在途登记的同时就要把在手扣掉。
    // 不扣就是虚增在手，7.1.1.1 的「在手 = 可售 + 待检 + 残次 + 在途」会当场失衡。
    if (kind == InTransitKind.FBA_OUTBOUND) {
      var balance =
          balanceId(
              tenantId, skuId, request.fromWarehouseId(), legalEntity, quality);
      deductSource(balance, qty, shippedAt);
      var txId = UUID.randomUUID();
      sql.execute(
          "insert into inventory_transaction(id,tenant_id,kind,ref_type,ref_id,occurred_at,actor_id,"
              + "notes) values(?,?,?,'IN_TRANSIT_LEG',?,cast(? as timestamptz),?,?)",
          txId,
          tenantId,
          "SHIPMENT",
          legId,
          shippedAt,
          UUID.randomUUID(),
          "FBA 发货在途登记：货离开发货仓，进入在途");
      sql.execute(
          "insert into inventory_entry(id,tenant_id,transaction_id,balance_id,on_hand_delta,"
              + "reserved_delta) values(?,?,?,?,?,0)",
          UUID.randomUUID(), tenantId, txId, balance, qty.negate());
    }

    // 占位符与绑定值一一对应：8 个普通列 + ref_id/shipped_at + transit_kind +
    // expected_arrive_at + 末段 7 列（lot_id…tracking_no），共 20 个 ? 对 20 个绑定值。
    sql.execute(
        "insert into inventory_transfer(id,tenant_id,sku_id,legal_entity_id,from_warehouse_id,"
            + "to_warehouse_id,quality_status_id,quantity,status,ref_type,ref_id,shipped_at,"
            + "transit_kind,expected_arrive_at,lot_id,source_system,source_reference,"
            + "from_warehouse_name,to_warehouse_name,carrier_name,tracking_no)"
            + " values(?,?,?,?,?,?,?,?,'IN_TRANSIT','IN_TRANSIT_LEG',?,cast(? as timestamptz),"
            + "?,cast(? as timestamptz),?,?,?,?,?,?,?)",
        legId,
        tenantId,
        skuId,
        legalEntity,
        request.fromWarehouseId(),
        request.toWarehouseId(),
        quality,
        qty,
        legId,
        shippedAt,
        kind.name(),
        eta,
        lotId,
        fit(sourceSystem, 32, "来源系统"),
        fit(sourceReference, 64, "来源单据号"),
        fromName,
        toName,
        fit(request.carrierName(), 100, "承运商"),
        fit(request.trackingNo(), 100, "运单号"));

    return savedOf(legId);
  }

  // ================================================================ 到达

  /**
   * 收货事件（到货）。
   *
   * <p>进我方仓的头程/采购在途：到货即入库目的仓（写 RECEIPT 分录），
   * 一次事件一笔分录，可分批到货 —— 分批到货在跨境头程里是常态，
   * 只允许「一次全到」会把真实的分批清关场景记成差异。
   *
   * <p>FBA 发货在途：到货只了结在途腿，**不入库** ——
   * 那一站的库存是平台仓的观测值（7.1.1.2 的事，尚未交付），本服务没有它的库位。
   */
  public InTransitSaved arrive(UUID legId, InTransitArriveRequest request) {
    Record leg = lockLeg(legId);
    requireVersion(leg, request.expectedVersion());
    String kind = leg.get("transit_kind", String.class);
    if (!InTransitKind.FBA_OUTBOUND.name().equals(kind)
        && request.locationId() == null) {
      throw new IllegalArgumentException(
          "进我方仓的到货必须指定落库库位（货到了哪个库位是收货必须回答的问题）");
    }
    OffsetDateTime at =
        request.occurredAt() == null ? OffsetDateTime.now() : request.occurredAt();
    BigDecimal qty = positive(request.quantity(), "本次到货数量");

    UUID tenantId = leg.get("tenant_id", UUID.class);
    UUID toWarehouse = leg.get("to_warehouse_id", UUID.class);
    UUID lotId = request.lotId() != null ? request.lotId() : leg.get("lot_id", UUID.class);
    if (lotId != null
        && sql.fetchOne(
                "select id from inventory_lot where tenant_id=? and id=? and sku_id=?",
                tenantId, lotId, leg.get("sku_id", UUID.class))
            == null) {
      throw new IllegalArgumentException("批次不存在、不属于该 SKU 或无权访问：" + lotId);
    }
    if (request.locationId() != null) {
      requireLocation(tenantId, request.locationId(), toWarehouse);
    }

    BigDecimal outstanding = decimal(leg, "quantity");
    if (qty.compareTo(outstanding) > 0) {
      throw new IllegalArgumentException(
          "本次到货数量超过该腿未达量（在途 " + outstanding + "，本次 " + qty + "）");
    }

    var eventId = UUID.randomUUID();
    sql.execute(
        "insert into in_transit_event(id,tenant_id,leg_id,kind,occurred_at,quantity,location_id,"
            + "lot_id,actor_id,notes) values(?,?,?,'ARRIVED',cast(? as timestamptz),?,?,?,?,?)",
        eventId,
        tenantId,
        legId,
        at,
        qty,
        request.locationId(),
        lotId,
        UUID.randomUUID(),
        fit(request.notes(), 2000, "到货备注"));

    if (!InTransitKind.FBA_OUTBOUND.name().equals(kind)) {
      var balance =
          balanceId(
              tenantId,
              leg.get("sku_id", UUID.class),
              toWarehouse,
              leg.get("legal_entity_id", UUID.class),
              leg.get("quality_status_id", UUID.class));
      addDestination(balance, request.locationId(), lotId, qty, at);
      var txId = UUID.randomUUID();
      sql.execute(
          "insert into inventory_transaction(id,tenant_id,kind,ref_type,ref_id,occurred_at,actor_id,"
              + "notes) values(?,?,?,'IN_TRANSIT_EVENT',?,cast(? as timestamptz),?,?)",
          txId,
          tenantId,
          "RECEIPT",
          eventId,
          at,
          UUID.randomUUID(),
          kindName(kind).label() + "到货入库：转入目的仓在手");
      sql.execute(
          "insert into inventory_entry(id,tenant_id,transaction_id,balance_id,on_hand_delta,"
              + "reserved_delta) values(?,?,?,?,?,0)",
          UUID.randomUUID(), tenantId, txId, balance, qty);
    }

    settle(legId, qty, at, "ARRIVED");
    return savedOf(legId);
  }

  /**
   * 差异登记：把到不了的部分如实记为短少，在途随之归零。
   *
   * <p>不自动抹平、也不悄悄挪回源仓 —— 短少就是短少，要留痕追责。
   */
  public InTransitSaved discrepancy(UUID legId, InTransitDiscrepancyRequest request) {
    Record leg = lockLeg(legId);
    requireVersion(leg, request.expectedVersion());
    var reason = InTransitReason.parse(required(request.reason(), "差异原因"));
    OffsetDateTime at =
        request.occurredAt() == null ? OffsetDateTime.now() : request.occurredAt();
    BigDecimal qty = positive(request.quantity(), "差异数量");

    BigDecimal outstanding = decimal(leg, "quantity");
    if (qty.compareTo(outstanding) > 0) {
      throw new IllegalArgumentException(
          "差异数量超过该腿未达量（在途 " + outstanding + "，本次差异 " + qty + "）");
    }

    var eventId = UUID.randomUUID();
    sql.execute(
        "insert into in_transit_event(id,tenant_id,leg_id,kind,occurred_at,quantity,location_id,"
            + "lot_id,actor_id,reason,notes) values(?,?,?,'SHORTAGE',cast(? as timestamptz),?,"
            + "null,null,?,?,?)",
        eventId,
        leg.get("tenant_id", UUID.class),
        legId,
        at,
        qty,
        UUID.randomUUID(),
        reason.name(),
        fit(request.notes(), 2000, "差异备注"));

    settle(legId, qty, at, "SHORTAGE");
    return savedOf(legId);
  }

  /**
   * 了结一段在途余量 —— 到达与差异登记**只有这一个收尾点**。
   *
   * <p>整腿了结就转终态（RECEIVED / CANCELLED，各自带 received_at，
   * V1 的 CHECK 要求非在途状态必须有收货时刻）；部分了结就把余量下调，
   * 腿继续挂在途上等下一笔。
   */
  private void settle(UUID legId, BigDecimal qty, OffsetDateTime at, String kind) {
    Record leg = lockLeg(legId);
    BigDecimal outstanding = decimal(leg, "quantity");
    BigDecimal rest = outstanding.subtract(qty);
    if (rest.signum() < 0) {
      throw new IllegalArgumentException("了结数量超过该腿未达量（在途 " + outstanding + "）");
    }
    String status = "ARRIVED".equals(kind) ? "RECEIVED" : "CANCELLED";
    if (rest.signum() == 0) {
      sql.execute(
          "update inventory_transfer set status=?,settled_quantity=settled_quantity+?,"
              + "received_at=cast(? as timestamptz),version=version+1,updated_at=now() where id=?",
          status, qty, at, legId);
    } else {
      sql.execute(
          "update inventory_transfer set quantity=?,settled_quantity=settled_quantity+?,"
              + "version=version+1,updated_at=now() where id=?",
          rest, qty, legId);
    }
  }

  // ================================================================ 行映射

  private InTransitRow row(Record r, OffsetDateTime now) {
    String kind = r.get("transit_kind", String.class);
    String status = r.get("status", String.class);
    BigDecimal settled = decimal(r, "settled_quantity");
    BigDecimal outstanding = decimal(r, "quantity");
    OffsetDateTime eta = r.get("expected_arrive_at", OffsetDateTime.class);
    var level = level(status, outstanding, eta, now);
    return new InTransitRow(
        r.get("id", UUID.class),
        kind,
        kindName(kind).label(),
        r.get("sku_id", UUID.class),
        r.get("sku_code", String.class),
        r.get("sku_name", String.class),
        r.get("quality_status_id", UUID.class),
        r.get("quality_status_code", String.class),
        r.get("legal_entity_id", UUID.class),
        r.get("legal_entity_code", String.class),
        r.get("from_warehouse_id", UUID.class),
        r.get("from_warehouse_code", String.class),
        displayName(r.get("from_warehouse_code", String.class), r.get("from_warehouse_name", String.class)),
        r.get("to_warehouse_id", UUID.class),
        r.get("to_warehouse_code", String.class),
        displayName(r.get("to_warehouse_code", String.class), r.get("to_warehouse_name", String.class)),
        r.get("lot_id", UUID.class),
        r.get("lot_code", String.class),
        outstanding,
        settled,
        declared(status, settled, outstanding),
        r.get("shipped_at", OffsetDateTime.class),
        eta,
        r.get("received_at", OffsetDateTime.class),
        status,
        level.name(),
        level.label(),
        overdueHours(outstanding, eta, now),
        r.get("carrier_name", String.class),
        r.get("tracking_no", String.class),
        r.get("source_system", String.class),
        r.get("source_reference", String.class),
        r.get("transfer_order_id", UUID.class),
        r.get("transfer_order_code", String.class),
        attribution(r),
        r.get("version", Long.class));
  }

  /**
   * 原发量 = 这一腿当初发出去了多少。
   *
   * <p>仍在途时 {@code = 已了结 + 未达}；已了结时 {@code = 已了结}。
   *
   * <p>为什么结案后不能还用「已了结 + 未达」：{@code inventory_transfer} 带着 V1 的
   * {@code CHECK(quantity>0)}，所以最后一段了结时 {@code quantity} 不能归零，
   * 它会**留在最后一段的余量上**。把这两列相加就会把那一段算两遍
   * （分两次了结 30 + 470 的腿会显示原发 970，而它只发了 500）。
   * 已了结的腿以 {@code settled_quantity} 为准 —— 那个数由到达事件的求和钉死（见 V63 的
   * {@code guard_in_transit_settled}），是这条腿上全部已了结量，不掺任何残留余量。
   */
  /**
   * 逾期档位：**已了结的腿一律不报逾期**。
   *
   * <p>注意不能只看「未达量 &gt; 0」：结案后 {@code quantity} 因 V1 的
   * {@code CHECK(quantity&gt;0)} 会留在最后一段余量上，它已经不是「还在路上」的量了。
   * 判据必须是状态，与 SQL 里的 {@link #overdueLevel(String)} 第一条判断同源。
   */
  private static InTransitOverdueLevel level(
      String status, BigDecimal outstanding, OffsetDateTime eta, OffsetDateTime now) {
    return "IN_TRANSIT".equals(status)
        ? InTransitOverdueLevel.of(outstanding, eta, now)
        : InTransitOverdueLevel.ON_TIME;
  }

  private static BigDecimal declared(String status, BigDecimal settled, BigDecimal outstanding) {
    return "IN_TRANSIT".equals(status) ? settled.add(outstanding) : settled;
  }

  /** 站名展示：在租户内以仓为准（权威），仓不在租户内才落快照。 */
  private static String displayName(String code, String snapshot) {
    return code != null ? code : snapshot;
  }

  /** 只有 ETA 已过且仍在途才算逾期小时数；其余返回 null 而不是 0（「没逾期」不是「逾期 0 小时」）。 */
  private static Long overdueHours(
      BigDecimal outstanding, OffsetDateTime eta, OffsetDateTime now) {
    if (eta == null || outstanding == null || outstanding.signum() <= 0 || !eta.isBefore(now)) {
      return null;
    }
    return java.time.Duration.between(eta, now).toHours();
  }

  /**
   * 口径归属说明，逐行返回。
   *
   * <p>不写这个字段的话同一张表里的三行会被读成三种互相矛盾的口径：
   * 调拨腿没有已了结量、头程腿登记时不在在手、FBA 腿登记时就已扣手。
   */
  private String attribution(Record r) {
    String kind = r.get("transit_kind", String.class);
    if (InTransitKind.TRANSFER.name().equals(kind)) {
      return "调拨在途：由 7.5.1.1 调拨单发出时产生，本模块只读。已了结量恒为 0 —— "
          + "该单的分次收货把到达量拆成了独立的已收行，本行的未达量就是「还差多少」，"
          + "到达的权威口径在调拨单的已实收列（详情里带出调拨单号）。";
    }
    if (InTransitKind.FBA_OUTBOUND.name().equals(kind)) {
      return "FBA 发货在途：登记时已从我方发货仓扣减在手（货真的离仓了），"
          + "到货只了结本腿、不入库 —— 平台仓库存是外部仓观测值（7.1.1.2，尚未交付），本服务没有它的库位。";
    }
    return kindName(kind).label() + "：登记时不动在手（货还没进我方账），到货才入库目的仓并写 RECEIPT 分录。"
        + "起止站里不在本租户仓内的一端用可读名快照（供应商工厂 / 平台仓不属本服务）。";
  }

  /** 详情页的指标说明：把这一腿的三个数量与它们的和讲清楚。 */
  private String metricNote(Record r) {
    return "原发 = 已了结 + 未达。已了结 = 已到达 + 已确认短少（短少如实记，不自动抹平）。"
        + "未达量是缺口计算要扣的数，与 7.1.1.1 / 7.2.2.1 / 7.1.3.1 求在途用的是同一个口径。"
        + attribution(r);
  }

  private InTransitSaved savedOf(UUID legId) {
    Record r = sql.fetchOne(legSelect("") + legFrom("") + " where t.id=?", legId);
    if (r == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "在途记录不存在或无权查看");
    }
    OffsetDateTime now = OffsetDateTime.now();
    BigDecimal settled = decimal(r, "settled_quantity");
    BigDecimal outstanding = decimal(r, "quantity");
    OffsetDateTime eta = r.get("expected_arrive_at", OffsetDateTime.class);
    String status = r.get("status", String.class);
    return new InTransitSaved(
        r.get("id", UUID.class),
        r.get("transit_kind", String.class),
        status,
        outstanding,
        settled,
        declared(status, settled, outstanding),
        eta,
        r.get("received_at", OffsetDateTime.class),
        level(status, outstanding, eta, now).name(),
        r.get("version", Long.class),
        attribution(r));
  }

  // ================================================================ 库存写入

  private UUID balanceId(
      UUID tenantId, UUID skuId, UUID warehouseId, UUID legalEntityId, UUID qualityStatusId) {
    var found =
        sql.fetchOne(
            "select id from inventory_balance where tenant_id=? and sku_id=? and warehouse_id=?"
                + " and legal_entity_id=? and quality_status_id=?",
            tenantId, skuId, warehouseId, legalEntityId, qualityStatusId);
    if (found != null) {
      return found.get("id", UUID.class);
    }
    var id = UUID.randomUUID();
    sql.execute(
        "insert into inventory_balance(id,tenant_id,sku_id,warehouse_id,legal_entity_id,"
            + "quality_status_id,on_hand,reserved) values(?,?,?,?,?,?,0,0)",
        id, tenantId, skuId, warehouseId, legalEntityId, qualityStatusId);
    return id;
  }

  /** 出库扣减：从库位按可用量依次扣，扣不动就报实账不符（与 7.5.1.1 同一套纪律）。 */
  private void deductSource(UUID balanceId, BigDecimal qty, OffsetDateTime at) {
    Record bal =
        sql.fetchOne("select on_hand,reserved,available from inventory_balance where id=?", balanceId);
    if (bal == null) {
      throw new IllegalArgumentException("发货仓没有这条库存余额，无法发出（货可能还没入库）");
    }
    BigDecimal available = decimal(bal, "available");
    if (qty.compareTo(available) > 0) {
      throw new IllegalArgumentException(
          "发货仓可用量不足：可用 " + available + "，本次发出 " + qty + "（已被预占的数量不能发走）");
    }
    var buckets =
        sql.fetch(
            "select id,available from stock_bucket where balance_id=? and available>0"
                + " order by last_movement_at,id for update",
            balanceId);
    if (buckets.isEmpty()) {
      sql.execute(
          "update inventory_balance set on_hand=on_hand-?,version=version+1,updated_at=now()"
              + " where id=?",
          qty, balanceId);
      return;
    }
    BigDecimal left = qty;
    for (var b : buckets) {
      if (left.signum() <= 0) {
        break;
      }
      UUID bucketId = b.get("id", UUID.class);
      BigDecimal take = decimal(b, "available").min(left);
      sql.execute(
          "update stock_bucket set on_hand=on_hand-?,"
              + " last_movement_at=greatest(cast(? as timestamptz),last_movement_at"
              + " + interval '1 microsecond') where id=?",
          take, at, bucketId);
      left = left.subtract(take);
    }
    if (left.signum() > 0) {
      throw new IllegalArgumentException("发货仓库位可用量与余额对不上，请先做一次账实核对");
    }
    sql.execute(
        "update inventory_balance set on_hand=on_hand-?,version=version+1,updated_at=now()"
            + " where id=?",
        qty, balanceId);
  }

  /** 入库加计：落既有库位/批次或新建 bucket 行，再抬余额。 */
  private void addDestination(
      UUID balanceId, UUID locationId, UUID lotId, BigDecimal qty, OffsetDateTime at) {
    Record existing =
        sql.fetchOne(
            "select id from stock_bucket where balance_id=? and location_id=?"
                + " and lot_id is not distinct from ?",
            balanceId, locationId, lotId);
    if (existing != null) {
      sql.execute(
          "update stock_bucket set on_hand=on_hand+?,"
              + " last_movement_at=greatest(cast(? as timestamptz),last_movement_at"
              + " + interval '1 microsecond') where id=?",
          qty, at, existing.get("id", UUID.class));
    } else {
      Record bal = sql.fetchOne("select * from inventory_balance where id=?", balanceId);
      sql.execute(
          "insert into stock_bucket(id,tenant_id,legal_entity_id,sku_id,warehouse_id,location_id,"
              + "lot_id,quality_status_id,balance_id,on_hand,reserved,last_movement_at)"
              + " values(?,?,?,?,?,?,?,?,?,?,0,cast(? as timestamptz))",
          UUID.randomUUID(),
          bal.get("tenant_id", UUID.class),
          bal.get("legal_entity_id", UUID.class),
          bal.get("sku_id", UUID.class),
          bal.get("warehouse_id", UUID.class),
          locationId,
          lotId,
          bal.get("quality_status_id", UUID.class),
          balanceId,
          qty,
          at);
    }
    sql.execute(
        "update inventory_balance set on_hand=on_hand+?,version=version+1,updated_at=now()"
            + " where id=?",
        qty, balanceId);
  }

  // ================================================================ 内部工具

  private Record lockLeg(UUID legId) {
    Record leg =
        sql.fetchOne("select * from inventory_transfer where id=? for update", legId);
    if (leg == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "在途记录不存在或无权查看");
    }
    if (!"IN_TRANSIT".equals(leg.get("status", String.class))) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "该在途腿已了结（状态 "
              + leg.get("status", String.class)
              + "），不能再登记到达或差异");
    }
    return leg;
  }

  /** 乐观锁：版本不符一律 409，不做「读到什么就写什么」。 */
  private void requireVersion(Record leg, Long expected) {
    if (expected == null) {
      throw new IllegalArgumentException("缺少 expectedVersion（并发到达/结案时必须给当前版本）");
    }
    long actual = leg.get("version", Long.class);
    if (actual != expected) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "版本不符：当前 " + actual + "，请求 " + expected + "（有人先改了这条在途记录，请刷新后重试）");
    }
  }

  private void requireLocation(UUID tenantId, UUID locationId, UUID warehouseId) {
    Record loc =
        sql.fetchOne(
            "select warehouse_id,status from warehouse_location where tenant_id=? and id=?",
            tenantId, locationId);
    if (loc == null) {
      throw new IllegalArgumentException("库位不存在或无权访问：" + locationId);
    }
    if (warehouseId != null && !warehouseId.equals(loc.get("warehouse_id", UUID.class))) {
      throw new IllegalArgumentException("该库位不属于本腿的目的仓（货不能收到别的仓去）");
    }
    if (!"ACTIVE".equals(loc.get("status", String.class))) {
      throw new IllegalArgumentException("该库位已停用，不能收货");
    }
  }

  private UUID tenant() {
    return sql.fetchOne("select nullif(current_setting('app.current_tenant',true),'')::uuid as t")
        .get("t", UUID.class);
  }

  private static InTransitKind kindName(String raw) {
    return InTransitKind.parse(raw);
  }

  private static String required(String value, String label) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(label + "必填");
    }
    return value.trim();
  }

  private static BigDecimal positive(BigDecimal value, String label) {
    if (value == null || value.signum() <= 0) {
      throw new IllegalArgumentException(label + "必须大于 0");
    }
    if (value.scale() > 4) {
      throw new IllegalArgumentException(label + "最多 4 位小数（与库存数量口径 numeric(19,4) 一致）");
    }
    return value;
  }

  private static String fit(String value, int max, String label) {
    if (value != null && value.length() > max) {
      throw new IllegalArgumentException(label + "长度不能超过 " + max + " 个字符");
    }
    return value == null ? "" : value;
  }

  private static BigDecimal decimal(Record r, String field) {
    var v = r.get(field, BigDecimal.class);
    return v == null ? BigDecimal.ZERO : v;
  }

  private static String placeholders(int n) {
    return String.join(",", java.util.Collections.nCopies(n, "?"));
  }

  private static List<Object> append(List<Object> binds, Object... more) {
    var out = new ArrayList<>(binds);
    out.addAll(Arrays.asList(more));
    return out;
  }
}
