package com.starcon.warehouse;

import static com.starcon.warehouse.InventoryTransferModels.*;

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
 * 7.5.1.1 调拨单。
 *
 * <p>蓝图：「发起仓库间调拨：选择源仓与目标仓、调拨 SKU 与数量、预计发出与到达时间，
 * 跟踪发出、在途、目标实收」；输出「调拨单（草稿/待发/在途/部分收/已收/差异）；
 * 库存分录（发出转入途、实收转入目标仓）」。
 *
 * <p>五条实现纪律：
 *
 * <ol>
 *   <li><b>调拨不是瞬时</b>（蓝图原文）。本服务**没有**「一次调用同时扣源仓加目标仓」的入口：
 *       {@link #ship} 只减源仓并挂 {@code inventory_transfer} 在途行，{@link #receive} 才是转入目的仓。
 *       「异地收发同一瞬间」被明确否掉了。
 *   <li><b>在途的归属写在 {@code inventory_transfer} 上</b>，且 {@code quantity} 是**余量**口径
 *       （发出时挂全额，实收时逐次下调，归零即置 RECEIVED）。四个既有模块
 *       （7.1.1.1 / 7.1.2.2 / 7.2.2.1 / 7.1.3.1）统一按
 *       {@code sum(quantity) where status='IN_TRANSIT'} 求在途，分次收货下它们一行都不用改就仍然正确。
 *   <li><b>乐观锁是硬约束</b>：蓝图把 {@code expected_version} 写进了输入契约，
 *       本服务每个写动作都先 {@code select ... for update} 锁单再比版本，不匹配一律 409，
 *       不做「读到什么就写什么」。
 *   <li><b>差异如实记录</b>：结案时把「已发出但永远收不到」的部分记成 {@code SHORTAGE} 事件
 *       并置在途行为 CANCELLED，<b>不</b>自动抹平、也不悄悄挪回源仓。
 *   <li><b>与 7.1.3.1 对齐</b>：调拨出库（TRANSFER_OUT）不是消耗。
 *       {@code InventoryDistributionService} 刻意把它排除在周转天数的出库速度之外，
 *       因为调拨决策正要用那一列算依据。本模块因此<b>不</b>把调拨算作销售/消耗，
 *       只如实记一笔出库分录（见下方 TRANSFER_OUT 的落账说明）。
 * </ol>
 *
 * <p>状态机只有一处真相：{@link #deriveStatus}。发出、实收、结案三个动作都不各自拼状态，
 * 而是改完数量后由它统一算出来 —— 三个地方各写一遍 if 的话，
 * 「收满最后一行到底该是已收还是部分收」这种边界迟早会写错一个。
 */
@Service
public class InventoryTransferService {

  /** 单页最大行数。 */
  static final int MAX_PAGE_SIZE = 200;

  private static final List<String> SORTS =
      List.of("CREATED_AT", "UPDATED_AT", "ORDER_CODE", "EXPECTED_ARRIVE", "EXPECTED_SHIP");

  /** 明细行的四种数量口径说明，随详情一起返回，界面上必须显示。 */
  private static final String CONSERVATION_NOTE =
      "已发出 = 已实收 + 已确认差异 + 仍在途。仍在途按 inventory_transfer 里 status='IN_TRANSIT' 的"
          + "余量实时求和，因此发货后货权已离开发货仓、实物尚未到达收货仓的那段时间不会凭空消失，"
          + "也不会重复计入任何一边的在手。未发出量（请求 − 已发出）不是差异：货从没离开源仓。";

  private final DSLContext sql;

  public InventoryTransferService(DSLContext sql) {
    this.sql = sql;
  }

  // ================================================================ 列表

  private static String orderFrom(String a) {
    return " from transfer_order o"
        + a
        + " join warehouse fw"
        + a
        + " on fw.tenant_id=o"
        + a
        + ".tenant_id and fw.id=o"
        + a
        + ".from_warehouse_id"
        + a
        + " join warehouse tw"
        + a
        + " on tw.tenant_id=o"
        + a
        + ".tenant_id and tw.id=o"
        + a
        + ".to_warehouse_id"
        + a
        + " join legal_entity_ref le"
        + a
        + " on le.tenant_id=o"
        + a
        + ".tenant_id and le.id=o"
        + a
        + ".legal_entity_id";
  }

  /**
   * 每单的数量聚合。
   *
   * <p>在途那一列是 {@code left join lateral} 到 inventory_transfer 的**余量**求和 ——
   * 它与 7.1.1.1 / 7.2.2.1 / 7.1.3.1 用的是同一条口径，不同模块不会给出两个在途数。
   *
   * <p>lateral 在没有明细行时也返回一行全 0，而不是消失：一张没有明细的调拨单
   * 不该从列表里「查不到」。
   */
  private static String orderAggJoin(String a) {
    return " left join lateral (select count(*) as line_count,"
        + " coalesce(sum(l.requested_quantity),0) as requested,"
        + " coalesce(sum(l.shipped_quantity),0) as shipped,"
        + " coalesce(sum(l.received_quantity),0) as received,"
        + " coalesce(sum(l.discrepancy_quantity),0) as discrepancy,"
        + " coalesce(sum(c.transit),0) as in_transit"
        + " from transfer_order_line l"
        + a
        + " left join lateral (select coalesce(sum(i.quantity),0) as transit"
        + " from inventory_transfer i where i.tenant_id=l"
        + a
        + ".tenant_id and i.line_id=l"
        + a
        + ".id and i.status='IN_TRANSIT') c"
        + a
        + " on true where l"
        + a
        + ".tenant_id=o"
        + a
        + ".tenant_id and l"
        + a
        + ".order_id=o"
        + a
        + ".id) agg"
        + a
        + " on true";
  }

  private static final String ROW_SELECT =
      """
      select o.id as order_id,o.order_code,o.from_warehouse_id,\
       fw.code as from_warehouse_code,fw.name as from_warehouse_name,\
       o.to_warehouse_id,tw.code as to_warehouse_code,tw.name as to_warehouse_name,\
       o.legal_entity_id,le.code as legal_entity_code,le.name as legal_entity_name,\
       o.status,o.expected_ship_at,o.expected_arrive_at,o.confirmed_at,o.shipped_at,o.completed_at,\
       o.reason,o.version,o.created_at,o.updated_at,\
       coalesce(agg.line_count,0) as line_count,\
       coalesce(agg.requested,0) as requested_quantity,\
       coalesce(agg.shipped,0) as shipped_quantity,\
       coalesce(agg.received,0) as received_quantity,\
       coalesce(agg.discrepancy,0) as discrepancy_quantity,\
       coalesce(agg.in_transit,0) as in_transit_quantity\
      """;

  /** 调拨单列表 + 全量汇总。 */
  public TransferOrderPage page(TransferOrderQuery query) {
    int size = Math.min(MAX_PAGE_SIZE, Math.max(1, query.size() == null ? 50 : query.size()));
    int pageNumber = Math.max(0, query.page() == null ? 0 : query.page());

    var where = new ArrayList<String>();
    var binds = new ArrayList<Object>();
    conditions(query, "", where, binds);
    String sqlWhere = where.isEmpty() ? "" : " where " + String.join(" and ", where);

    long total =
        sql.fetchOne("select count(*)" + orderFrom("") + sqlWhere, binds.toArray()).get(0, Long.class);

    var rows =
        sql.fetch(
            ROW_SELECT
                + orderFrom("")
                + orderAggJoin("")
                + sqlWhere
                + order(query)
                + " limit ? offset ?",
            append(binds, size, (long) pageNumber * size).toArray());

    return new TransferOrderPage(
        summary(query),
        rows.stream().map(InventoryTransferService::row).toList(),
        statusOptions(),
        warehouseOptions(),
        total,
        pageNumber,
        size);
  }

  /** 列表汇总。**全量**，不受分页影响。 */
  private TransferOrderSummary summary(TransferOrderQuery query) {
    var where = new ArrayList<String>();
    var binds = new ArrayList<Object>();
    conditions(query, "", where, binds);
    String sqlWhere = where.isEmpty() ? "" : " where " + String.join(" and ", where);

    Record r =
        sql.fetchOne(
            "select count(*) as order_count,"
                + " coalesce(sum(agg.line_count),0) as line_count,"
                + " coalesce(sum(agg.requested),0) as requested,"
                + " coalesce(sum(agg.shipped),0) as shipped,"
                + " coalesce(sum(agg.received),0) as received,"
                + " coalesce(sum(agg.discrepancy),0) as discrepancy,"
                + " coalesce(sum(agg.in_transit),0) as in_transit,"
                + " count(*) filter (where o.status='DRAFT') as draft_count,"
                + " count(*) filter (where o.status='PENDING_SHIPMENT') as pending_count,"
                + " count(*) filter (where o.status='IN_TRANSIT') as in_transit_count,"
                + " count(*) filter (where o.status='PARTIALLY_RECEIVED') as partial_count,"
                + " count(*) filter (where o.status='RECEIVED') as received_count,"
                + " count(*) filter (where o.status='DISCREPANCY') as discrepancy_count,"
                + " min(o.expected_arrive_at) as earliest_expected_arrive"
                + orderFrom("")
                + orderAggJoin("")
                + sqlWhere,
            binds.toArray());

    return new TransferOrderSummary(
        r.get("order_count", Long.class),
        r.get("line_count", Long.class),
        decimal(r, "requested"),
        decimal(r, "shipped"),
        decimal(r, "received"),
        decimal(r, "discrepancy"),
        decimal(r, "in_transit"),
        r.get("draft_count", Long.class),
        r.get("pending_count", Long.class),
        r.get("in_transit_count", Long.class),
        r.get("partial_count", Long.class),
        r.get("received_count", Long.class),
        r.get("discrepancy_count", Long.class),
        r.get("earliest_expected_arrive", OffsetDateTime.class));
  }

  /**
   * 状态词表：六态全列（含当前没有单的态），带真实单数。
   *
   * <p>刻意**不**只列「有单的」—— 一个「差异」态下拉项哪怕现在是 0，也要让人知道系统认这个态。
   */
  private List<TransferOption> statusOptions() {
    var counts = new java.util.HashMap<String, Long>();
    for (var row :
        sql.fetch("select status,count(*) as c from transfer_order group by status")) {
      counts.put(row.get("status", String.class), row.get("c", Long.class));
    }
    var out = new ArrayList<TransferOption>();
    for (var s : TransferOrderStatus.values()) {
      out.add(new TransferOption(s.name(), s.label(), counts.getOrDefault(s.name(), 0L)));
    }
    return out;
  }

  /** 仓库下拉：源仓与目标仓共用同一份清单。 */
  private List<TransferWarehouseOption> warehouseOptions() {
    return sql.fetch(
            "select w.id,w.code,w.name from warehouse w where w.status<>'ARCHIVED' order by w.code")
        .map(r -> new TransferWarehouseOption(r.get("id", UUID.class),
            r.get("code", String.class), r.get("name", String.class)));
  }

  private void conditions(
      TransferOrderQuery q, String a, List<String> where, List<Object> binds) {
    if (q.fromWarehouseId() != null) {
      where.add("o" + a + ".from_warehouse_id=?");
      binds.add(q.fromWarehouseId());
    }
    if (q.toWarehouseId() != null) {
      where.add("o" + a + ".to_warehouse_id=?");
      binds.add(q.toWarehouseId());
    }
    if (q.skuId() != null) {
      where.add(
          "exists (select 1 from transfer_order_line l"
              + a
              + " join sku_ref s"
              + a
              + " on s"
              + a
              + ".tenant_id=l"
              + a
              + ".tenant_id and s"
              + a
              + ".id=l"
              + a
              + ".sku_id where l"
              + a
              + ".tenant_id=o"
              + a
              + ".tenant_id and l"
              + a
              + ".order_id=o"
              + a
              + ".id and l"
              + a
              + ".sku_id=?)");
      binds.add(q.skuId());
    }
    if (q.status() != null && !q.status().isEmpty()) {
      var codes = new ArrayList<String>();
      for (var raw : q.status()) {
        if (raw == null || raw.isBlank()) {
          continue;
        }
        codes.add(TransferOrderStatus.parse(raw.trim()).name());
      }
      if (!codes.isEmpty()) {
        where.add("o" + a + ".status in (" + placeholders(codes.size()) + ")");
        binds.addAll(codes);
      }
    }
    if (q.keyword() != null && !q.keyword().isBlank()) {
      var k = "%" + q.keyword().trim() + "%";
      where.add(
          "(o"
              + a
              + ".order_code ilike ? or o"
              + a
              + ".reason ilike ? or exists (select 1 from transfer_order_line l"
              + a
              + " join sku_ref s"
              + a
              + " on s"
              + a
              + ".tenant_id=l"
              + a
              + ".tenant_id and s"
              + a
              + ".id=l"
              + a
              + ".sku_id where l"
              + a
              + ".tenant_id=o"
              + a
              + ".tenant_id and l"
              + a
              + ".order_id=o"
              + a
              + ".id and (s"
              + a
              + ".code ilike ? or s"
              + a
              + ".name ilike ?)))");
      for (int i = 0; i < 4; i++) {
        binds.add(k);
      }
    }
  }

  /** 排序。决胜键恒定追加 {@code o.id}，避免同值翻页错位。 */
  private String order(TransferOrderQuery q) {
    String sort = q.sort() == null ? "CREATED_AT" : q.sort().toUpperCase(Locale.ROOT);
    if (!SORTS.contains(sort)) {
      throw new IllegalArgumentException(
          "不支持的排序键：" + q.sort() + "，可选：" + String.join("、", SORTS));
    }
    return switch (sort) {
      case "UPDATED_AT" -> " order by o.updated_at desc,o.id";
      case "ORDER_CODE" -> " order by o.order_code,o.id";
      case "EXPECTED_ARRIVE" -> " order by o.expected_arrive_at asc nulls last,o.id";
      case "EXPECTED_SHIP" -> " order by o.expected_ship_at asc nulls last,o.id";
      default -> " order by o.created_at desc,o.id";
    };
  }

  // ================================================================ 详情

  /**
   * 调拨单详情：头 + 明细 + 每次发出 + 每次收货/差异登记。
   *
   * <p>跨租户在 RLS 下查不到 → 404（403 会泄露「这张单存在」）。
   */
  public TransferOrderDetail detail(UUID orderId) {
    Record header =
        sql.fetchOne(ROW_SELECT + orderFrom("") + orderAggJoin("") + " where o.id=?", orderId);
    if (header == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "调拨单不存在或无权查看");
    }

    var lines =
        sql.fetch(
                "select l.id,l.sku_id,s.code as sku_code,s.name as sku_name,l.quality_status_id,"
                    + " q.code as quality_status_code,q.name as quality_status_name,"
                    + " l.requested_quantity,l.shipped_quantity,l.received_quantity,"
                    + " l.discrepancy_quantity,l.version,"
                    + " (select coalesce(sum(i.quantity),0) from inventory_transfer i"
                    + "  where i.tenant_id=l.tenant_id and i.line_id=l.id and i.status='IN_TRANSIT')"
                    + "   as in_transit_quantity"
                    + " from transfer_order_line l"
                    + " join sku_ref s on s.tenant_id=l.tenant_id and s.id=l.sku_id"
                    + " join inventory_quality_status q"
                    + " on q.tenant_id=l.tenant_id and q.id=l.quality_status_id"
                    + " where l.order_id=? order by s.code,q.code",
                orderId)
            .map(
                r ->
                    new TransferOrderLineRow(
                        r.get("id", UUID.class),
                        r.get("sku_id", UUID.class),
                        r.get("sku_code", String.class),
                        r.get("sku_name", String.class),
                        r.get("quality_status_id", UUID.class),
                        r.get("quality_status_code", String.class),
                        r.get("quality_status_name", String.class),
                        decimal(r, "requested_quantity"),
                        decimal(r, "shipped_quantity"),
                        decimal(r, "received_quantity"),
                        decimal(r, "discrepancy_quantity"),
                        decimal(r, "in_transit_quantity"),
                        decimal(r, "requested_quantity").subtract(decimal(r, "shipped_quantity")),
                        r.get("version", Long.class)));

    var shipments =
        sql.fetch(
                "select sh.id,sh.shipped_at,sh.actor_id,sh.notes from transfer_shipment sh"
                    + " where sh.order_id=? order by sh.shipped_at,sh.id",
                orderId)
            .map(
                r ->
                    new TransferShipmentRow(
                        r.get("id", UUID.class),
                        r.get("shipped_at", OffsetDateTime.class),
                        r.get("actor_id", UUID.class),
                        r.get("notes", String.class),
                        shipmentLines(orderId, r.get("id", UUID.class))));

    var receipts =
        sql.fetch(
                "select rc.id,rc.kind,rc.received_at,rc.location_id,loc.code as location_code,"
                    + " rc.actor_id,rc.reason,rc.notes from transfer_receipt rc"
                    + " left join warehouse_location loc"
                    + " on loc.tenant_id=rc.tenant_id and loc.id=rc.location_id"
                    + " where rc.order_id=? order by rc.received_at,rc.id",
                orderId)
            .map(
                r ->
                    new TransferReceiptRow(
                        r.get("id", UUID.class),
                        r.get("kind", String.class),
                        r.get("received_at", OffsetDateTime.class),
                        r.get("location_id", UUID.class),
                        r.get("location_code", String.class),
                        r.get("actor_id", UUID.class),
                        r.get("reason", String.class),
                        r.get("notes", String.class),
                        receiptLines(r.get("id", UUID.class))));

    return new TransferOrderDetail(row(header), lines, shipments, receipts, CONSERVATION_NOTE);
  }

  /**
   * 一次发出动作覆盖了哪些行、各发多少。
   *
   * <p><b>刻意从 inventory_entry 派生，而不是读 inventory_transfer</b>：部分收货会把在途行
   * 拆成「已收的一段 + 未到的剩余」，读在途行只能看到拆完之后的形状，读不出这一趟原本发了多少。
   * 发出量这个事实本来就该取自 append-only 的分录（迁移 V61 的注释里也是这么写的），
   * 台账是权威出处，在途表只是「当前还有多少在路上」的余额行。
   */
  private List<TransferShipmentLineRow> shipmentLines(UUID orderId, UUID shipmentId) {
    return sql.fetch(
            "select l.id as line_id,s.code as sku_code,sum(-e.on_hand_delta) as quantity"
                + " from inventory_transaction t"
                + " join inventory_entry e on e.transaction_id=t.id"
                + " join inventory_balance bal on bal.id=e.balance_id"
                + " join transfer_order_line l on l.order_id=? and l.sku_id=bal.sku_id"
                + "   and l.quality_status_id=bal.quality_status_id"
                + " join sku_ref s on s.tenant_id=l.tenant_id and s.id=l.sku_id"
                + " where t.ref_type='TRANSFER_ORDER' and t.kind='TRANSFER_OUT' and t.ref_id=?"
                + " group by l.id,s.code order by s.code",
            orderId, shipmentId)
        .map(
            r ->
                new TransferShipmentLineRow(
                    r.get("line_id", UUID.class),
                    r.get("sku_code", String.class),
                    decimal(r, "quantity")));
  }

  private List<TransferReceiptLineRow> receiptLines(UUID receiptId) {
    return sql.fetch(
            "select rl.line_id,s.code as sku_code,rl.quantity from transfer_receipt_line rl"
                + " join transfer_order_line l on l.tenant_id=rl.tenant_id and l.id=rl.line_id"
                + " join sku_ref s on s.tenant_id=l.tenant_id and s.id=l.sku_id"
                + " where rl.receipt_id=? order by s.code",
            receiptId)
        .map(
            r ->
                new TransferReceiptLineRow(
                    r.get("line_id", UUID.class),
                    r.get("sku_code", String.class),
                    decimal(r, "quantity")));
  }

  // ================================================================ 建单与改单

  /**
   * 建单（草稿）。
   *
   * <p>单号由 DB 序列生成（{@code TR-yyyyMMdd-000001}），不在应用层拼 ——
   * 应用层拼出来的单号并发下必然撞号，而撞号会被 UNIQUE 变成 409，那不该发生。
   *
   * <p>建单**不动库存**：草稿只是意图。货权在 ship 那一刻才离开发货仓。
   */
  public TransferOrderSaved create(UUID tenantId, TransferOrderCreateRequest request) {
    UUID from = request.fromWarehouseId();
    UUID to = request.toWarehouseId();
    if (from == null || to == null) {
      throw new IllegalArgumentException("调拨必须同时指定源仓与目标仓");
    }
    if (from.equals(to)) {
      throw new IllegalArgumentException("源仓与目标仓不能是同一个仓（同仓调拨不产生在途）");
    }
    requireWarehouse(tenantId, from, "源仓");
    requireWarehouse(tenantId, to, "目标仓");
    if (request.legalEntityId() == null) {
      throw new IllegalArgumentException("调拨必须指定货权法人：这批货算哪个法人的账");
    }
    if (sql.fetchOne(
            "select id from legal_entity_ref where id=? and tenant_id=?", request.legalEntityId(),
            tenantId)
        == null) {
      throw new IllegalArgumentException("货权法人不存在或无权访问：" + request.legalEntityId());
    }
    checkWindow(request.expectedShipAt(), request.expectedArriveAt());

    var lines = request.lines();
    if (lines == null || lines.isEmpty()) {
      throw new IllegalArgumentException("调拨单至少要有一条明细");
    }

    var id = UUID.randomUUID();
    // 单号 = TR-当天日期-六位序号。序号与日期分两步取，不在一条 SQL 里拼：
    // 纯文本语句里的 `::text` 这类表达式 jOOQ 解析器吃不准，拆开就没有歧义。
    long seq = sql.fetchOne("select nextval('transfer_order_code_seq')").get(0, Long.class);
    var today = sql.fetchOne("select current_date").get(0, java.time.LocalDate.class);
    String code = "TR-" + today.toString().replace("-", "") + "-" + String.format("%06d", seq);
    sql.execute(
        "insert into transfer_order(id,tenant_id,order_code,from_warehouse_id,to_warehouse_id,"
            + "legal_entity_id,status,expected_ship_at,expected_arrive_at,reason,notes,created_by)"
            + " values(?,?,?,?,?,?,?,cast(? as timestamptz),cast(? as timestamptz),?,?,?)",
        id,
        tenantId,
        code,
        from,
        to,
        request.legalEntityId(),
        TransferOrderStatus.DRAFT.name(),
        request.expectedShipAt(),
        request.expectedArriveAt(),
        fit(request.reason(), 200, "调拨原因"),
        fit(request.notes(), 2000, "备注"),
        UUID.randomUUID());
    insertLines(tenantId, id, lines);
    return savedOf(id);
  }

  /**
   * 改单：仅草稿可改，且整份替换明细。
   *
   * <p>发出之后一行都不让改：明细行上的在途腿已经指着它（{@code inventory_transfer.line_id}），
   * 改数量会让在途清单与明细永久对不上。要改请新建单。
   */
  public TransferOrderSaved update(UUID orderId, TransferOrderUpdateRequest request) {
    Record order = lockOrder(orderId);
    requireVersion(order, request.expectedVersion());
    requireStatus(order, TransferOrderStatus.DRAFT);
    checkWindow(request.expectedShipAt(), request.expectedArriveAt());

    var lines = request.lines();
    if (lines == null || lines.isEmpty()) {
      throw new IllegalArgumentException("调拨单至少要有一条明细");
    }
    sql.execute("delete from transfer_order_line where order_id=?", orderId);
    insertLines(order.get("tenant_id", UUID.class), orderId, lines);
    sql.execute(
        "update transfer_order set expected_ship_at=cast(? as timestamptz),"
            + " expected_arrive_at=cast(? as timestamptz),reason=?,notes=?,"
            + " version=version+1,updated_at=now() where id=?",
        request.expectedShipAt(),
        request.expectedArriveAt(),
        fit(request.reason(), 200, "调拨原因"),
        fit(request.notes(), 2000, "备注"),
        orderId);
    return savedOf(orderId);
  }

  /** 草稿 → 待发。这是唯一一次「确认」，确认之后明细锁定。 */
  public TransferOrderSaved confirm(UUID orderId, TransferOrderActionRequest request) {
    Record order = lockOrder(orderId);
    requireVersion(order, request.expectedVersion());
    requireStatus(order, TransferOrderStatus.DRAFT);
    sql.execute(
        "update transfer_order set status=?,confirmed_at=now(),version=version+1,updated_at=now()"
            + " where id=?",
        TransferOrderStatus.PENDING_SHIPMENT.name(), orderId);
    return savedOf(orderId);
  }

  private void insertLines(UUID tenantId, UUID orderId, List<TransferOrderLineRequest> lines) {
    var seen = new java.util.LinkedHashSet<String>();
    for (var line : lines) {
      if (line.skuId() == null) {
        throw new IllegalArgumentException("每条明细都要指定 SKU");
      }
      if (line.quantity() == null || line.quantity().signum() <= 0) {
        throw new IllegalArgumentException("每条明细的调拨数量必须大于 0");
      }
      if (sql.fetchOne("select id from sku_ref where id=? and tenant_id=?", line.skuId(), tenantId)
          == null) {
        throw new IllegalArgumentException("SKU 不存在或无权访问：" + line.skuId());
      }
      if (line.qualityStatusId() == null
          || sql.fetchOne(
                  "select id from inventory_quality_status where id=? and tenant_id=?",
                  line.qualityStatusId(), tenantId)
              == null) {
        throw new IllegalArgumentException(
            "质量状态不存在或无权访问：" + line.qualityStatusId());
      }
      var key = line.skuId() + "/" + line.qualityStatusId();
      if (!seen.add(key)) {
        throw new IllegalArgumentException("同一 SKU 与质量状态在明细里出现了多行，请合并为一行");
      }
      sql.execute(
          "insert into transfer_order_line(id,tenant_id,order_id,sku_id,quality_status_id,"
              + "requested_quantity) values(?,?,?,?,?,?)",
          UUID.randomUUID(),
          tenantId,
          orderId,
          line.skuId(),
          line.qualityStatusId(),
          line.quantity());
    }
  }

  // ================================================================ 发出：减源仓 + 挂���途

  /**
   * 发出：减源仓余额、写 TRANSFER_OUT 分录、**挂上在途**。
   *
   * <p>这一步<em>不</em>动目的仓 —— 货还没到。这是本模块与「异地收发同一瞬间」的分界线。
   *
   * <p>可分批发运：同一张单多次调用，每次发一部分行或每行的一部分量。
   *
   * <p>库位扣减：V2 的守恒触发器要求「改余额必须同时改库位明细」，
   * 所以扣减按库位逐个扣（{@code available>0} 的库位，按最近变动时间先后）。
   * 余额行尚未分解到库位时（V1 允许的合法状态）只改余额。
   */
  public TransferOrderSaved ship(UUID orderId, TransferShipRequest request) {
    Record order = lockOrder(orderId);
    requireVersion(order, request.expectedVersion());
    String status = order.get("status", String.class);
    if (!status.equals(TransferOrderStatus.PENDING_SHIPMENT.name())
        && !status.equals(TransferOrderStatus.IN_TRANSIT.name())
        && !status.equals(TransferOrderStatus.PARTIALLY_RECEIVED.name())) {
      throw statusError(status, "只有待发、在途或部分收的调拨单可以发出");
    }
    UUID tenantId = order.get("tenant_id", UUID.class);
    UUID actor = UUID.randomUUID();
    OffsetDateTime shippedAt =
        request.shippedAt() == null ? OffsetDateTime.now() : request.shippedAt();

    var lines = request.lines();
    if (lines == null || lines.isEmpty()) {
      throw new IllegalArgumentException("本次发出没有指定任何明细行");
    }

    var shipmentId = UUID.randomUUID();
    sql.execute(
        "insert into transfer_shipment(id,tenant_id,order_id,shipped_at,actor_id,notes)"
            + " values(?,?,?,cast(? as timestamptz),?,?)",
        shipmentId,
        tenantId,
        orderId,
        shippedAt,
        actor,
        fit(request.notes(), 2000, "发出备注"));

    UUID transactionId = UUID.randomUUID();
    sql.execute(
        "insert into inventory_transaction(id,tenant_id,kind,ref_type,ref_id,occurred_at,actor_id,"
            + "notes) values(?,?,?,?,?,cast(? as timestamptz),?,?)",
        transactionId,
        tenantId,
        "TRANSFER_OUT",
        "TRANSFER_ORDER",
        shipmentId,
        shippedAt,
        actor,
        "调拨发出：货权离开发货仓，货进入在途");

    for (var line : lines) {
      BigDecimal qty = positive(line.quantity(), "本次发出数量");
      Record l = requireLine(orderId, line.lineId());
      BigDecimal remaining = requestedMinusShipped(l);
      if (qty.compareTo(remaining) > 0) {
        throw new IllegalArgumentException(
            "本次发出数量超过该行未发数量（请求 "
                + decimal(l, "requested_quantity")
                + "，已发 "
                + decimal(l, "shipped_quantity")
                + "，本次 "
                + qty
                + "）");
      }
      UUID skuId = l.get("sku_id", UUID.class);
      UUID quality = l.get("quality_status_id", UUID.class);
      UUID balanceId =
          balanceId(
              tenantId,
              skuId,
              order.get("from_warehouse_id", UUID.class),
              order.get("legal_entity_id", UUID.class),
              quality);
      deductSource(balanceId, qty, shippedAt);
      sql.execute(
          "insert into inventory_entry(id,tenant_id,transaction_id,balance_id,on_hand_delta,"
              + "reserved_delta) values(?,?,?,?,?,0)",
          UUID.randomUUID(), tenantId, transactionId, balanceId, qty.negate());
      sql.execute(
          "insert into inventory_transfer(id,tenant_id,sku_id,legal_entity_id,from_warehouse_id,"
              + "to_warehouse_id,quality_status_id,quantity,status,ref_type,ref_id,shipped_at,line_id)"
              + " values(?,?,?,?,?,?,?,?,'IN_TRANSIT','TRANSFER_SHIPMENT',?,cast(? as timestamptz),?)",
          UUID.randomUUID(),
          tenantId,
          skuId,
          order.get("legal_entity_id", UUID.class),
          order.get("from_warehouse_id", UUID.class),
          order.get("to_warehouse_id", UUID.class),
          quality,
          qty,
          shipmentId,
          shippedAt,
          l.get("id", UUID.class));
      sql.execute(
          "update transfer_order_line set shipped_quantity=shipped_quantity+?,version=version+1,"
              + "updated_at=now() where id=?",
          qty, l.get("id", UUID.class));
    }

    syncStatus(orderId, tenantId);
    return savedOf(orderId);
  }

  /**
   * 从源仓余额扣减，并同步扣减库位明细。
   *
   * <p>可用量（{@code on_hand − reserved}）不足直接报错：调走的是**可发**的货，
   * 动到已预占的数量会把别人的订单掏空，而 {@code available>=0} 那条 CHECK 只是最后一道兜底。
   */
  private void deductSource(UUID balanceId, BigDecimal qty, OffsetDateTime at) {
    Record bal =
        sql.fetchOne("select on_hand,reserved,available from inventory_balance where id=?", balanceId);
    if (bal == null) {
      throw new IllegalArgumentException("源仓没有这条库存余额，无法调拨（货可能还没入库）");
    }
    BigDecimal available = decimal(bal, "available");
    if (qty.compareTo(available) > 0) {
      throw new IllegalArgumentException(
          "源仓可用量不足：可用 "
              + available
              + "，本次发出 "
              + qty
              + "（已被预占的数量不能调走）");
    }
    var buckets =
        sql.fetch(
            "select id,available from stock_bucket where balance_id=? and available>0"
                + " order by last_movement_at,id for update",
            balanceId);
    if (buckets.isEmpty()) {
      // 余额行尚未分解到库位（V1 允许的合法状态）：只改余额。
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
      // last_movement_at 必须严格前进（V2 的 guard_bucket_movement_time 会挡），
      // 业务时间早于既有变动时间时用「原值 + 1 微秒」保证单调，绝不倒退。
      sql.execute(
          "update stock_bucket set on_hand=on_hand-?,"
              + " last_movement_at=greatest(cast(? as timestamptz),last_movement_at"
              + " + interval '1 microsecond') where id=?",
          take, at, bucketId);
      left = left.subtract(take);
    }
    if (left.signum() > 0) {
      throw new IllegalArgumentException("源仓库位可用量与余额对不上，请先做一次账实核对");
    }
    sql.execute(
        "update inventory_balance set on_hand=on_hand-?,version=version+1,updated_at=now()"
            + " where id=?",
        qty, balanceId);
  }

  // ================================================================ 实收：转入目的仓 + 冲减在途

  /**
   * 实收：转入目的仓余额、写 TRANSFER_IN 分录、**逐次冲减在途**。
   *
   * <p>可分次收货（蓝图：「目的仓分次收货逐笔转入」）。每次调用是一笔独立事件，
   * 落到同一���目的库位，因此只有一处 {@code locationId}。
   *
   * <p>在途余量按「先发先收」冲减：{@code inventory_transfer.quantity} 是余量口径，
   * 归零即置 RECEIVED 并写收货时刻。这让 7.1.1.1 / 7.2.2.1 / 7.1.3.1 的
   * {@code sum(quantity) where status='IN_TRANSIT'} 在分次收货下依然精确。
   */
  public TransferOrderSaved receive(UUID orderId, TransferReceiveRequest request) {
    Record order = lockOrder(orderId);
    requireVersion(order, request.expectedVersion());
    String status = order.get("status", String.class);
    if (!status.equals(TransferOrderStatus.IN_TRANSIT.name())
        && !status.equals(TransferOrderStatus.PARTIALLY_RECEIVED.name())) {
      throw statusError(status, "只有在途或部分收的调拨单可以收货");
    }
    UUID tenantId = order.get("tenant_id", UUID.class);
    UUID toWarehouse = order.get("to_warehouse_id", UUID.class);
    UUID actor = UUID.randomUUID();
    OffsetDateTime receivedAt =
        request.receivedAt() == null ? OffsetDateTime.now() : request.receivedAt();

    if (request.locationId() == null) {
      throw new IllegalArgumentException("收货必须指定落库库位");
    }
    requireLocation(tenantId, request.locationId(), toWarehouse);
    if (request.lotId() != null
        && sql.fetchOne("select id from inventory_lot where id=? and tenant_id=?", request.lotId(),
            tenantId)
            == null) {
      throw new IllegalArgumentException("批次不存在或无权访问：" + request.lotId());
    }

    var lines = request.lines();
    if (lines == null || lines.isEmpty()) {
      throw new IllegalArgumentException("本次收货没有指定任何明细行");
    }

    var receiptId = UUID.randomUUID();
    sql.execute(
        "insert into transfer_receipt(id,tenant_id,order_id,kind,received_at,location_id,actor_id,"
            + "notes) values(?,?,?,'RECEIVED',cast(? as timestamptz),?,?,?)",
        receiptId,
        tenantId,
        orderId,
        receivedAt,
        request.locationId(),
        actor,
        fit(request.notes(), 2000, "收货备注"));

    UUID transactionId = UUID.randomUUID();
    sql.execute(
        "insert into inventory_transaction(id,tenant_id,kind,ref_type,ref_id,occurred_at,actor_id,"
            + "notes) values(?,?,?,?,?,cast(? as timestamptz),?,?)",
        transactionId,
        tenantId,
        "TRANSFER_IN",
        "TRANSFER_ORDER",
        receiptId,
        receivedAt,
        actor,
        "调拨实收：货物到达目的仓，转入该仓在手");

    for (var line : lines) {
      BigDecimal qty = positive(line.quantity(), "本次实收数量");
      Record l = requireLine(orderId, line.lineId());
      BigDecimal outstanding = inTransitOf(l.get("id", UUID.class));
      if (qty.compareTo(outstanding) > 0) {
        throw new IllegalArgumentException(
            "本次实收数量超过该行仍在途的数量（在途 "
                + outstanding
                + "，本次 "
                + qty
                + "）");
      }
      UUID balanceId =
          balanceId(
              tenantId,
              l.get("sku_id", UUID.class),
              toWarehouse,
              order.get("legal_entity_id", UUID.class),
              l.get("quality_status_id", UUID.class));
      addDestination(balanceId, request.locationId(), request.lotId(), qty, receivedAt);
      sql.execute(
          "insert into inventory_entry(id,tenant_id,transaction_id,balance_id,on_hand_delta,"
              + "reserved_delta) values(?,?,?,?,?,0)",
          UUID.randomUUID(), tenantId, transactionId, balanceId, qty);
      sql.execute(
          "insert into transfer_receipt_line(id,tenant_id,receipt_id,line_id,quantity)"
              + " values(?,?,?,?,?)",
          UUID.randomUUID(), tenantId, receiptId, l.get("id", UUID.class), qty);
      consumeInTransit(l.get("id", UUID.class), qty, receivedAt, receiptId);
      sql.execute(
          "update transfer_order_line set received_quantity=received_quantity+?,"
              + "version=version+1,updated_at=now() where id=?",
          qty, l.get("id", UUID.class));
    }

    syncStatus(orderId, tenantId);
    return savedOf(orderId);
  }

  /** 目的仓入库：加余额 + 同步加库位明细（V2 守恒）。 */
  private void addDestination(
      UUID balanceId, UUID locationId, UUID lotId, BigDecimal qty, OffsetDateTime at) {
    Record existing =
        sql.fetchOne("select id from stock_bucket where balance_id=? and location_id=? and lot_id is not distinct from ?",
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

  /**
   * 按「先发先收」冲减在途行。
   *
   * <p>V1 给 {@code inventory_transfer} 留了两条 CHECK，收尾方式是被它们决定的：
   *
   * <ul>
   *   <li>{@code CHECK(quantity>0)} —— 不允许把余量记成 0。所以「这一腿收完了」不能靠归零表示。
   *   <li>{@code CHECK((status='IN_TRANSIT')=(received_at IS NULL))} —— 非在途状态**必须**带一个
   *       了结时刻：RECEIVED 记实收时刻，CANCELLED 记结案了结时刻。
   * </ul>
   *
   * <p>因此部分收货走「拆行」而不是「改一行的余量」：
   * <b>原行改成还没到的剩余量，另起一行记已收到的那一段</b>。
   * 这样 7.1.1.1 / 7.2.2.1 / 7.1.3.1 那条 {@code sum(quantity) where status='IN_TRANSIT'}
   * 得到的仍是**精确的未达量**，而两条 CHECK 都还成立。
   */
  private void consumeInTransit(UUID lineId, BigDecimal qty, OffsetDateTime at, UUID receiptId) {
    BigDecimal left = qty;
    var legs =
        sql.fetch(
            "select id,quantity from inventory_transfer where line_id=? and status='IN_TRANSIT'"
                + " order by shipped_at,id for update",
            lineId);
    for (var leg : legs) {
      if (left.signum() <= 0) {
        break;
      }
      BigDecimal have = decimal(leg, "quantity");
      BigDecimal take = have.min(left);
      BigDecimal rest = have.subtract(take);
      UUID legId = leg.get("id", UUID.class);
      if (rest.signum() == 0) {
        // 整腿收完：原行转 RECEIVED，quantity 保持发出量（V1 的 CHECK 要求 quantity>0）
        sql.execute(
            "update inventory_transfer set status='RECEIVED',"
                + "received_at=cast(? as timestamptz),updated_at=now() where id=?",
            at, legId);
      } else {
        // 原行留下未到的剩余量
        sql.execute(
            "update inventory_transfer set quantity=?,updated_at=now() where id=?", rest, legId);
        // 已收到的那一段另起一行；归属指回本次收货事件，维度列整份复制自原行
        sql.execute(
            "insert into inventory_transfer(id,tenant_id,sku_id,legal_entity_id,from_warehouse_id,"
                + "to_warehouse_id,quality_status_id,quantity,status,ref_type,ref_id,shipped_at,"
                + "received_at,line_id)"
                + " select ?,tenant_id,sku_id,legal_entity_id,from_warehouse_id,to_warehouse_id,"
                + "quality_status_id,?,'RECEIVED','TRANSFER_RECEIPT',?,shipped_at,"
                + "cast(? as timestamptz),line_id from inventory_transfer where id=?",
            UUID.randomUUID(), take, receiptId, at, legId);
      }
      left = left.subtract(take);
    }
  }

  // ================================================================ 结案：差异如实记录

  /**
   * 结案：把「已发出但永远收不到」的部分如实记成差异，在途归零。
   *
   * <p>**不自动抹平**：差异既不会被悄悄挪回源仓，也不会被算成源仓的入库。
   * 它就是这趟货真的丢了/破损了/被拒收了，原因是必填的 {@code SHORTAGE} 事件。
   *
   * <p>在途行为 CANCELLED 且 quantity 归零：否则这批货会永远挂在在途清单上，
   * 而 7.1.1.1 的守恒式（在手 = 可售 + 待检 + 残次 + 在途）会把货算成还在路上。
   *
   * <p>没有短少时结案直接落「已收」终态。
   */
  public TransferOrderSaved close(UUID orderId, TransferCloseRequest request) {
    Record order = lockOrder(orderId);
    requireVersion(order, request.expectedVersion());
    String status = order.get("status", String.class);
    if (!status.equals(TransferOrderStatus.IN_TRANSIT.name())
        && !status.equals(TransferOrderStatus.PARTIALLY_RECEIVED.name())) {
      throw statusError(status, "只有在途或部分收的调拨单可以结案");
    }
    UUID tenantId = order.get("tenant_id", UUID.class);
    UUID actor = UUID.randomUUID();
    OffsetDateTime at = OffsetDateTime.now();

    var outstanding =
        sql.fetch(
            "select l.id,l.shipped_quantity,l.received_quantity,"
                + " (select coalesce(sum(i.quantity),0) from inventory_transfer i"
                + "  where i.tenant_id=l.tenant_id and i.line_id=l.id and i.status='IN_TRANSIT')"
                + "   as in_transit from transfer_order_line l where l.order_id=? for update",
            orderId);

    String reason = request.reason() == null ? null : request.reason().trim();
    for (var l : outstanding) {
      BigDecimal transit = decimal(l, "in_transit");
      if (transit.signum() <= 0) {
        continue;
      }
      if (reason == null || reason.isEmpty()) {
        throw new IllegalArgumentException(
            "存在已发出但未实收的货物（差异 " + transit + "），结案必须给出差异原因");
      }
      var parsed = TransferDiscrepancyReason.parse(reason);
      var receiptId = UUID.randomUUID();
      sql.execute(
          "insert into transfer_receipt(id,tenant_id,order_id,kind,received_at,actor_id,reason,notes)"
              + " values(?,?,?,'SHORTAGE',cast(? as timestamptz),?,?,?)",
          receiptId, tenantId, orderId, at, actor, parsed.name(),
          fit(request.notes(), 2000, "结案备注"));
      sql.execute(
          "insert into transfer_receipt_line(id,tenant_id,receipt_id,line_id,quantity)"
              + " values(?,?,?,?,?)",
          UUID.randomUUID(), tenantId, receiptId, l.get("id", UUID.class), transit);
      // 在途归零：状态 CANCELLED。这批货已经不在路上了。
      // V1 的 CHECK 规定非在途状态必须带一个了结时刻，CANCELLED 就记结案时刻。
      sql.execute(
          "update inventory_transfer set status='CANCELLED',"
              + "received_at=cast(? as timestamptz),updated_at=now()"
              + " where line_id=? and status='IN_TRANSIT'",
          at, l.get("id", UUID.class));
      sql.execute(
          "update transfer_order_line set discrepancy_quantity=discrepancy_quantity+?,"
              + "version=version+1,updated_at=now() where id=?",
          transit, l.get("id", UUID.class));
    }

    // 结案备注一律留痕（有没有短少都留）：谁在什么时候把这趟货关掉了本身就是审计事实。
    // 在 syncStatus 之前写，并自带 version 递增 —— 否则改了数据却不推进乐观锁版本，
    // 下一次带旧版本的并发请求就会被静默放行。
    sql.execute(
        "update transfer_order set notes=coalesce(nullif(?,''),notes),version=version+1,"
            + "updated_at=now() where id=?",
        fit(request.notes(), 2000, "结案备注"), orderId);

    syncStatus(orderId, tenantId);
    return savedOf(orderId);
  }

  // ================================================================ 状态机（唯一真相）

  /**
   * 依据明细行的真实数量重算单据状态。
   *
   * <p>发出、实收、结案三个动作都调它 —— 状态不写在各自的 if 里，
   * 「最后一行收完到底是已收还是部分收」这种边界只需要在这里判断一次。
   *
   * <p>判定顺序即优先级：终态优先，其次「有无实收」，最后「发没发」。
   */
  private void syncStatus(UUID orderId, UUID tenantId) {
    BigDecimal shipped = BigDecimal.ZERO;
    BigDecimal received = BigDecimal.ZERO;
    BigDecimal discrepancy = BigDecimal.ZERO;
    BigDecimal transit = BigDecimal.ZERO;
    // 在途那一列写成只绑 order_id 的独立子查询（**不引用外层的 l**）：
    // 聚合查询的 SELECT 里放一个引用未分组列的相关子查询，PG 会直接报
    // 「subquery uses ungrouped column」，而那条报错完全指不到真正的原因。
    for (var r :
        sql.fetch(
            "select coalesce(sum(l.shipped_quantity),0) as shipped,"
                + " coalesce(sum(l.received_quantity),0) as received,"
                + " coalesce(sum(l.discrepancy_quantity),0) as discrepancy,"
                + " (select coalesce(sum(i.quantity),0) from inventory_transfer i"
                + "  where i.tenant_id=? and i.status='IN_TRANSIT' and i.line_id in"
                + "   (select id from transfer_order_line where order_id=?)) as transit"
                + " from transfer_order_line l where l.order_id=?",
            tenantId, orderId, orderId)) {
      shipped = decimal(r, "shipped");
      received = decimal(r, "received");
      discrepancy = decimal(r, "discrepancy");
      transit = decimal(r, "transit");
    }

    TransferOrderStatus next;
    if (discrepancy.signum() > 0) {
      next = TransferOrderStatus.DISCREPANCY;
    } else if (transit.signum() == 0 && received.compareTo(shipped) == 0 && shipped.signum() > 0) {
      next = TransferOrderStatus.RECEIVED;
    } else if (received.signum() > 0) {
      next = TransferOrderStatus.PARTIALLY_RECEIVED;
    } else if (shipped.signum() > 0) {
      next = TransferOrderStatus.IN_TRANSIT;
    } else {
      next = TransferOrderStatus.PENDING_SHIPMENT;
    }

    boolean terminal =
        next == TransferOrderStatus.RECEIVED || next == TransferOrderStatus.DISCREPANCY;

    // 时间戳在 Java 侧算好再写死，不在 SQL 里用 `case when ?>?` 判：
    // 纯文本语句里那个写法 jOOQ 判不出绑定类型，报出来的错又完全指不到真因。
    // 首发出货时间只补一次（coalesce 语义），终态完成时间只补一次。
    var now = OffsetDateTime.now();
    Record stamps =
        sql.fetchOne("select shipped_at,completed_at from transfer_order where id=?", orderId);
    OffsetDateTime shippedAt = stamps.get("shipped_at", OffsetDateTime.class);
    if (shippedAt == null && shipped.signum() > 0) {
      shippedAt = now;
    }
    OffsetDateTime completedAt = stamps.get("completed_at", OffsetDateTime.class);
    if (terminal && completedAt == null) {
      completedAt = now;
    }
    if (!terminal) {
      completedAt = null;
    }
    sql.execute(
        "update transfer_order set status=?,shipped_at=cast(? as timestamptz),"
            + "completed_at=cast(? as timestamptz),version=version+1,updated_at=now() where id=?",
        next.name(), shippedAt, completedAt, orderId);
  }

  // ================================================================ 校验与工具

  /** 锁单 + 读租户。并发发货/收货时同一张单被串行化，版本比对才可靠。 */
  private Record lockOrder(UUID orderId) {
    Record order = sql.fetchOne("select * from transfer_order where id=? for update", orderId);
    if (order == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "调拨单不存在或无权查看");
    }
    return order;
  }

  /** 乐观锁：蓝图把 expected_version 写进输入契约，版本不符一律 409，绝不静默覆盖。 */
  private void requireVersion(Record order, Long expected) {
    if (expected == null) {
      throw new IllegalArgumentException("缺少 expectedVersion：写操作必须带上乐观锁版本号");
    }
    long actual = order.get("version", Long.class);
    if (actual != expected) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "调拨单已被他人修改（当前版本 " + actual + "，你的版本 " + expected + "），请刷新后重试");
    }
  }

  private void requireStatus(Record order, TransferOrderStatus expected) {
    String actual = order.get("status", String.class);
    if (!actual.equals(expected.name())) {
      throw statusError(actual, "当前状态「" + expected.label() + "」才能执行该操作");
    }
  }

  private static ResponseStatusException statusError(String actual, String hint) {
    var known = TransferOrderStatus.parse(actual).label();
    return new ResponseStatusException(HttpStatus.CONFLICT, "调拨单当前是「" + known + "」：" + hint);
  }

  private Record requireLine(UUID orderId, UUID lineId) {
    if (lineId == null) {
      throw new IllegalArgumentException("必须指定调拨明细行");
    }
    Record line = sql.fetchOne("select * from transfer_order_line where id=? and order_id=?",
        lineId, orderId);
    if (line == null) {
      throw new IllegalArgumentException("明细行不属于这张调拨单：" + lineId);
    }
    return line;
  }

  private void requireWarehouse(UUID tenantId, UUID warehouseId, String label) {
    if (sql.fetchOne("select id from warehouse where id=? and tenant_id=? and status='ACTIVE'",
        warehouseId, tenantId) == null) {
      throw new IllegalArgumentException(label + "不存在、不属于本租户或已停用：" + warehouseId);
    }
  }

  private void requireLocation(UUID tenantId, UUID locationId, UUID warehouseId) {
    if (sql.fetchOne(
            "select id from warehouse_location where id=? and tenant_id=? and warehouse_id=?",
            locationId, tenantId, warehouseId) == null) {
      throw new IllegalArgumentException(
          "落库库位不存在，或不属于目标仓（收货只能落到目的仓的库位）：" + locationId);
    }
  }

  private static void checkWindow(OffsetDateTime shipAt, OffsetDateTime arriveAt) {
    if (shipAt != null && arriveAt != null && arriveAt.isBefore(shipAt)) {
      throw new IllegalArgumentException("预计到达时间不能早于预计发出时间");
    }
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

  private static BigDecimal requestedMinusShipped(Record line) {
    return decimal(line, "requested_quantity").subtract(decimal(line, "shipped_quantity"));
  }

  /** 该行当前仍在途的余量。与 7.1.1.1 等模块同一口径。 */
  private BigDecimal inTransitOf(UUID lineId) {
    return sql.fetchOne(
            "select coalesce(sum(quantity),0) from inventory_transfer"
                + " where line_id=? and status='IN_TRANSIT'",
            lineId)
        .get(0, BigDecimal.class);
  }

  /** 取（或建）一条余额行。跨租户塞进来的仓/SKU 会在这里被 UNIQUE 或 RLS 挡住。 */
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

  private static String fit(String value, int max, String label) {
    if (value != null && value.length() > max) {
      throw new IllegalArgumentException(label + "长度不能超过 " + max + " 个字符");
    }
    return value == null ? "" : value;
  }

  private static String placeholders(int count) {
    return String.join(",", java.util.Collections.nCopies(count, "?"));
  }

  private static List<Object> append(List<Object> binds, Object... more) {
    var all = new ArrayList<>(binds);
    all.addAll(Arrays.asList(more));
    return all;
  }

  /**
   * 读金额/数量。
   *
   * <p>纯文本 SQL 里的表达式列 jOOQ 拿不到列类型，直接 {@code get(field, BigDecimal.class)}
   * 可能静默返回 null（批次模块的 {@code days_to_expiry} 就是这么整列变成 null 的）。
   * 这里收口成不依赖驱动返回类型的读法。
   */
  private static BigDecimal decimal(Record r, String field) {
    var value = r.get(field);
    if (value == null) {
      return BigDecimal.ZERO;
    }
    if (value instanceof BigDecimal d) {
      return d;
    }
    if (value instanceof Number n) {
      return new BigDecimal(n.toString());
    }
    return new BigDecimal(value.toString());
  }

  private static TransferOrderRow row(Record r) {
    return new TransferOrderRow(
        r.get("order_id", UUID.class),
        r.get("order_code", String.class),
        r.get("from_warehouse_id", UUID.class),
        r.get("from_warehouse_code", String.class),
        r.get("from_warehouse_name", String.class),
        r.get("to_warehouse_id", UUID.class),
        r.get("to_warehouse_code", String.class),
        r.get("to_warehouse_name", String.class),
        r.get("legal_entity_id", UUID.class),
        r.get("legal_entity_name", String.class),
        r.get("status", String.class),
        r.get("expected_ship_at", OffsetDateTime.class),
        r.get("expected_arrive_at", OffsetDateTime.class),
        r.get("confirmed_at", OffsetDateTime.class),
        r.get("shipped_at", OffsetDateTime.class),
        r.get("completed_at", OffsetDateTime.class),
        decimal(r, "requested_quantity"),
        decimal(r, "shipped_quantity"),
        decimal(r, "received_quantity"),
        decimal(r, "discrepancy_quantity"),
        decimal(r, "in_transit_quantity"),
        r.get("line_count", Long.class),
        r.get("reason", String.class),
        r.get("version", Long.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  /** 建单/改单/发货/收货/结案后回读：响应是「库里真实存了什么」。 */
  private TransferOrderSaved savedOf(UUID orderId) {
    Record r =
        sql.fetchOne(
            "select o.id,o.order_code,o.status,o.version,o.confirmed_at,o.shipped_at,o.completed_at,"
                + " coalesce(agg.requested,0) as requested,coalesce(agg.shipped,0) as shipped,"
                + " coalesce(agg.received,0) as received,coalesce(agg.discrepancy,0) as discrepancy,"
                + " coalesce(agg.in_transit,0) as in_transit"
                + " from transfer_order o"
                + orderAggJoin("")
                + " where o.id=?",
            orderId);
    if (r == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "调拨单不存在或无权查看");
    }
    return new TransferOrderSaved(
        r.get("id", UUID.class),
        r.get("order_code", String.class),
        r.get("status", String.class),
        r.get("version", Long.class),
        decimal(r, "requested"),
        decimal(r, "shipped"),
        decimal(r, "received"),
        decimal(r, "discrepancy"),
        decimal(r, "in_transit"),
        r.get("confirmed_at", OffsetDateTime.class),
        r.get("shipped_at", OffsetDateTime.class),
        r.get("completed_at", OffsetDateTime.class));
  }
}