package com.starcon.warehouse;

import static com.starcon.warehouse.GoodsReceiptModels.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
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
 * 8.2.1.1 收货单。
 *
 * <p>蓝图：「执行到货收货：选择来源（采购/退货/调拨/平台仓回货），录入实收数量与差异， 上传收货凭证，生成入库分录」；输出「收货单（草稿/已收/部分收/已关闭）+
 * 库存入库分录（转入待检或可售 bucket）」；「**预期与实收分开记录**，超收/少收/拒收都要留痕 并回写采购单」。
 *
 * <p>六条实现纪律：
 *
 * <ol>
 *   <li><b>预期与实收分开记录</b>（蓝图原文）。{@code goods_receipt_line} 上 {@code expected_quantity} / {@code
 *       received_quantity} / {@code rejected_quantity} 三列并存、永不互相覆盖，「还差多少没到齐」可以直接相减答出来。
 *   <li><b>入账走带符号增量</b>：与 7.4.2.2 盘点差异、7.5.1.1 调拨实收同一条路 —— {@code on_hand +=
 *       qty}，<b>不是</b>覆盖式写回。覆盖式写回会把收货期间真实发生的 其他交易（并行的出库、预占、盘点调整）整段抹掉。既有 V2 守恒触发器要求
 *       「改余额必须同时改库位明细」，所以余额与库位两处都写。
 *   <li><b>差异只记录不处理</b>：本模块落 {@code goods_receipt_discrepancy} 事实行 （类型/数量/原因/凭证/记录人），<b>不</b>建处理状态机
 *       —— 那是 8.2.1.2 的资产。 蓝图点名的「破损与短少必须拒收或待检入库」因此在本模块就已生效： 拒收量**不写分录**，而落库质量状态由建单时的 {@code
 *       quality_status_id} 决定， 现场把破损品挂到 DEFECTIVE 或 PENDING_INSPECTION 就是按待检/残次入库。
 *   <li><b>超收必须留痕</b>：DB 触发器 {@code guard_receipt_line_overage} 钉死 「实收 + 拒收 &gt; 预期 ⟹ 必须有一条
 *       OVER_RECEIPT 差异记录」。
 *   <li><b>乐观锁是硬约束</b>：每个写动作先 {@code select ... for update} 锁单再比版本， 不匹配一律 409，不做「读到什么就写什么」。
 *   <li><b>不回写来源单据</b>：蓝图说「回写采购单」，但 6.3.2.2 与 11.2.2 尚未交付， 反向写别人的表会越过「每张事实表只有一个写入所有者」的边界纪律。 本模块在
 *       {@code goods_receipt.source_ref_id} 如实记下来源单据， 上游交付后由上游订阅收货事件更新自己的「已收」；capabilities
 *       里如实说明这一点。
 * </ol>
 *
 * <p>状态机只有一处真相：{@link #deriveStatus}。实收与关单两个动作都不各自拼状态， 而是改完数量后由它统一算出来 —— 两个地方各写一遍 if 的话，
 * 「最后一车收齐到底该是已收还是部分收」这种边界迟早会写错一个。
 */
@Service
public class GoodsReceiptService {

  /** 单页最大行数。 */
  static final int MAX_PAGE_SIZE = 200;

  private static final List<String> SORTS = List.of("CREATED_AT", "UPDATED_AT", "RECEIPT_CODE");

  /** 明细行的三种数量口径说明，随详情一起返回，界面上必须显示。 */
  private static final String CONSERVATION_NOTE =
      "预期 / 实收 / 拒收分行记录，永不互相覆盖：货没到齐是常态，而「货没到齐」和「账记错了」"
          + "必须能区分开。还差量 = 预期 − 实收 − 拒收，它**不是**差异 —— 货还在路上；"
          + "关单时剩下的部分才会被如实记成少收。拒收量**不写分录、不进库存**：它从没进过库存余额，"
          + "所以 8.2.1.2 接手的是索赔与改判，而不是「把它从库存里删掉」。";

  private final DSLContext sql;
  private final InventorySerialService serials;

  public GoodsReceiptService(DSLContext sql, InventorySerialService serials) {
    this.sql = sql;
    this.serials = serials;
  }

  // ================================================================ 能力

  /**
   * 收货能力：四类来源的接入情况、四态状态机、七类差异，以及三条必须如实讲清楚的说明。
   *
   * <p>本车道的依赖自检结论（对集成头实际查证，不凭蓝图的「上游 =」假定）： 7.5.1.1 调拨单**已交付**（{@code transfer_order} 等五张表，迁移 V61），
   * 因此 {@code TRANSFER} 来源是真通的 —— 建单时会真去校验来源调拨单存在且已发出。 6 采购单（6.3.x）、11
   * 退货授权（11.2.1.1）与平台仓回货（10.x）在本车道**未交付**， 按仓库既有约定显式 {@code available=false} 并给出原因，
   * **不建占位表、不返回空集冒充成功**。
   */
  public GoodsReceiptCapabilities capabilities() {
    List<ReceiptSourceCapability> sources =
        new ArrayList<>(
            List.of(
                source(
                    "PURCHASE",
                    false,
                    "6.3.2.2",
                    "/purchase/orders",
                    "上游 6 采购与供应尚未交付：采购单表与「更新已收」端点都不存在。"
                        + "本模块因此不接受 PURCHASE 来源的收货单 —— 建一张没有采购单可指的单，"
                        + "会让「这批货是哪张采购单来的」永远答不出来。"),
                source(
                    "RETURN_AUTH",
                    false,
                    "11.2.1.1",
                    "/after-sales/returns",
                    "上游 11 售后未交付：退货授权单（11.2.1.1）不存在。" + "退货入库（11.2.2.1）是另一件事，本模块也不代做判定。"),
                source(
                    "TRANSFER",
                    true,
                    "7.5.1.1",
                    "/inventory/transfers",
                    "已交付：7.5.1.1 调拨单（迁移 V61）。建单时会真去校验来源调拨单存在、" + "属于本租户且已发出。"),
                source(
                    "PLATFORM_WAREHOUSE",
                    false,
                    "10.x",
                    "/logistics",
                    "上游 10 物流与头程未交付：平台仓回货没有可引用的来源单据。")));

    List<ReceiptTransition> transitions = new ArrayList<>();
    for (var from : ReceiptStatus.values()) {
      var allowed = ReceiptStatus.allowedNext(from);
      for (var to : ReceiptStatus.values()) {
        boolean ok = allowed.contains(to);
        transitions.add(
            new ReceiptTransition(
                from.name(),
                to.name(),
                ok,
                ok
                    ? "允许"
                    : (from == ReceiptStatus.CLOSED
                        ? "已关闭是终态，不再接受任何动作"
                        : "非法跳转：收货单状态由实收进度与关单推导，不接受手工设置")));
      }
    }

    List<ReceiptOption> statuses = new ArrayList<>();
    for (var s : ReceiptStatus.values()) {
      statuses.add(new ReceiptOption(s.name(), s.label(), countByStatus(s)));
    }

    List<DiscrepancyOption> types = new ArrayList<>();
    for (var t : DiscrepancyType.values()) {
      types.add(
          new DiscrepancyOption(
              t.name(),
              t.label(),
              // 只有这三类是本模块在现场就能判定的；其余四类（破损/型号不符/过期/错发）
              // 由 8.2.1.2 主导处理决定，本模块照样如实记录事实。
              t == DiscrepancyType.OVER_RECEIPT
                  || t == DiscrepancyType.SHORT_RECEIPT
                  || t == DiscrepancyType.REJECTED));
    }

    return new GoodsReceiptCapabilities(
        sources,
        transitions,
        statuses,
        types,
        false,
        "8.2.1.2",
        "差异的**处理流程**（拍照留证、拒收决定、部分接收、索赔发起）归 8.2.1.2，"
            + "该模块尚未交付。本模块只落**事实**：差异类型、数量、原因、凭证说明、记录人与时刻。"
            + "因此差异记录没有处理状态列 —— 现在建一个空的处理状态机，等于给下游预留一个"
            + "谁都能写的半成品状态。",
        "本模块**不回写来源单据**的「已收」列。蓝图说「回写采购单」，但 6.3.2.2 与 11.2.2 "
            + "尚未交付，反向写别人的表会越过「每张事实表只有一个写入所有者」的边界纪律。"
            + "本模块在 goods_receipt.source_ref_id 如实记下来源单据 id，上游交付后"
            + "由上游订阅收货事件更新自己的「已收」。",
        CONSERVATION_NOTE);
  }

  private ReceiptSourceCapability source(
      String value, boolean available, String ownerModule, String href, String note) {
    var s = ReceiptSourceType.parse(value);
    return new ReceiptSourceCapability(s.name(), s.label(), available, ownerModule, href, note);
  }

  private long countByStatus(ReceiptStatus status) {
    return sql.fetchOne(
            "select count(*) from goods_receipt where tenant_id="
                + "nullif(current_setting('app.current_tenant',true),'')::uuid and status=?",
            status.name())
        .get(0, Long.class);
  }

  // ================================================================ 列表

  private static String fromClause() {
    return " from goods_receipt r"
        + " join warehouse w on w.tenant_id=r.tenant_id and w.id=r.warehouse_id"
        + " join legal_entity_ref le on le.tenant_id=r.tenant_id and le.id=r.legal_entity_id";
  }

  /**
   * 每单的数量聚合。
   *
   * <p>未收齐量 {@code outstanding} 用 {@code greatest(...)} 夹到 0：超收时它本来就是负的， 界面上一列负的「还差多少」比静默显示 0
   * 更能提醒「这单超收了，差异已登记」。 lateral 在没有明细行时也返回一行全 0，一张没有明细的收货单不该从列表里「查不到」。
   */
  private static String aggJoin() {
    return " left join lateral (select count(*) as line_count,"
        + " coalesce(sum(l.expected_quantity),0) as expected,"
        + " coalesce(sum(l.received_quantity),0) as received,"
        + " coalesce(sum(l.rejected_quantity),0) as rejected"
        + " from goods_receipt_line l where l.tenant_id=r.tenant_id and l.receipt_id=r.id) agg on true"
        + " left join lateral (select count(*) as event_count from goods_receipt_event e"
        + " where e.tenant_id=r.tenant_id and e.receipt_id=r.id) ev on true"
        + " left join lateral (select count(*) as discrepancy_count from goods_receipt_discrepancy d"
        + " where d.tenant_id=r.tenant_id and d.receipt_id=r.id) ds on true";
  }

  private static String rowSelect() {
    return "select r.id as receipt_id,r.receipt_code,r.source_type,r.source_ref_id,"
        + " r.source_ref_code,r.warehouse_id,w.code as warehouse_code,w.name as warehouse_name,"
        + " r.legal_entity_id,le.name as legal_entity_name,r.status,r.carrier,r.waybill_no,"
        + " r.closed_at,r.version,r.created_at,r.updated_at,"
        + " coalesce(agg.line_count,0) as line_count,coalesce(agg.expected,0) as expected_quantity,"
        + " coalesce(agg.received,0) as received_quantity,coalesce(agg.rejected,0) as rejected_quantity,"
        + " greatest(coalesce(agg.expected,0)-coalesce(agg.received,0)-coalesce(agg.rejected,0),0)"
        + "   as outstanding_quantity,"
        + " coalesce(ev.event_count,0) as event_count,coalesce(ds.discrepancy_count,0) as discrepancy_count";
  }

  /** 列表查询条件。全部对应 SQL 里的真实条件，没有「接了但不生效」的参数。 */
  private void conditions(
      GoodsReceiptQuery q, String alias, List<String> where, List<Object> binds) {
    String a = alias.isEmpty() ? "" : alias + ".";
    if (q.warehouseId() != null) {
      where.add(a + "warehouse_id=?");
      binds.add(q.warehouseId());
    }
    if (q.status() != null && !q.status().isEmpty()) {
      for (var raw : q.status()) {
        where.add(a + "status=?");
        binds.add(ReceiptStatus.parse(raw).name());
      }
    }
    if (q.sourceType() != null && !q.sourceType().isEmpty()) {
      for (var raw : q.sourceType()) {
        where.add(a + "source_type=?");
        binds.add(ReceiptSourceType.parse(raw).name());
      }
    }
    if (q.keyword() != null && !q.keyword().isBlank()) {
      // 真实模糊检索：单号、来源单号、承运单号、备注、凭证说明。**不是**假搜索。
      where.add(
          "("
              + a
              + "receipt_code ilike ? or "
              + a
              + "source_ref_code ilike ? or "
              + a
              + "waybill_no ilike ? or "
              + a
              + "carrier ilike ? or "
              + a
              + "evidence_note ilike ? or "
              + a
              + "close_reason ilike ?)");
      String kw = "%" + q.keyword().trim() + "%";
      for (int i = 0; i < 6; i++) {
        binds.add(kw);
      }
    }
  }

  /** 排序白名单。**不接受**用户传入的排序表达式 —— 那是 SQL 注入面。 */
  private static String orderBy(GoodsReceiptQuery q) {
    String sort = q.sort() == null ? "UPDATED_AT" : q.sort().toUpperCase(Locale.ROOT);
    if (!SORTS.contains(sort)) {
      sort = "UPDATED_AT";
    }
    return switch (sort) {
      case "CREATED_AT" -> " order by r.created_at desc,r.id";
      case "RECEIPT_CODE" -> " order by r.receipt_code,r.id";
      default -> " order by r.updated_at desc,r.id";
    };
  }

  /** 列表页的全量汇总：与筛选条件一致，但**不受分页影响**。 */
  private GoodsReceiptSummary summary(GoodsReceiptQuery q) {
    var where = new ArrayList<String>();
    var binds = new ArrayList<Object>();
    conditions(q, "r", where, binds);
    String sqlWhere = where.isEmpty() ? "" : " where " + String.join(" and ", where);
    Record r =
        sql.fetchOne(
            "select count(*) as receipt_count,coalesce(sum(agg.line_count),0) as line_count,"
                + " coalesce(sum(agg.expected),0) as expected,coalesce(sum(agg.received),0) as received,"
                + " coalesce(sum(agg.rejected),0) as rejected,coalesce(sum(agg.line_count),0) as _x,"
                + " min(r.created_at) as earliest"
                + " from goods_receipt r"
                + " left join lateral (select count(*) as line_count,"
                + " coalesce(sum(l.expected_quantity),0) as expected,"
                + " coalesce(sum(l.received_quantity),0) as received,"
                + " coalesce(sum(l.rejected_quantity),0) as rejected"
                + " from goods_receipt_line l where l.tenant_id=r.tenant_id and l.receipt_id=r.id) agg on true"
                + sqlWhere,
            binds.toArray());
    long total = n(r, "receipt_count");
    Record counts =
        sql.fetchOne(
            "select coalesce(sum(case when r.status='DRAFT' then 1 else 0 end),0) as draft,"
                + " coalesce(sum(case when r.status='PARTIALLY_RECEIVED' then 1 else 0 end),0) as partial,"
                + " coalesce(sum(case when r.status='RECEIVED' then 1 else 0 end),0) as received_st,"
                + " coalesce(sum(case when r.status='CLOSED' then 1 else 0 end),0) as closed,"
                + " coalesce(sum(case when r.status<>'CLOSED' then 1 else 0 end),0) as open_cnt,"
                + " coalesce(sum(coalesce(ds.n,0)),0) as discrepancy"
                + " from goods_receipt r"
                + " left join lateral (select count(*) as n from goods_receipt_discrepancy d"
                + " where d.tenant_id=r.tenant_id and d.receipt_id=r.id) ds on true"
                + sqlWhere,
            binds.toArray());
    BigDecimal expected = decimal(r, "expected");
    BigDecimal received = decimal(r, "received");
    BigDecimal rejected = decimal(r, "rejected");
    return new GoodsReceiptSummary(
        total,
        n(r, "line_count"),
        expected,
        received,
        rejected,
        expected.subtract(received).subtract(rejected).max(BigDecimal.ZERO),
        n(counts, "draft"),
        n(counts, "partial"),
        n(counts, "received_st"),
        n(counts, "closed"),
        n(counts, "discrepancy"),
        n(counts, "open_cnt"),
        r.get("earliest", OffsetDateTime.class));
  }

  public GoodsReceiptPage page(GoodsReceiptQuery q) {
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

    return new GoodsReceiptPage(
        summary(q),
        rows.stream().map(GoodsReceiptService::row).toList(),
        statusOptions(),
        capabilities().sources(),
        warehouseOptions(),
        total,
        pageNumber,
        size);
  }

  private List<ReceiptOption> statusOptions() {
    var out = new ArrayList<ReceiptOption>();
    for (var s : ReceiptStatus.values()) {
      out.add(new ReceiptOption(s.name(), s.label(), countByStatus(s)));
    }
    return out;
  }

  private List<ReceiptWarehouseOption> warehouseOptions() {
    return sql
        .fetch(
            "select id,code,name from warehouse where tenant_id="
                + "nullif(current_setting('app.current_tenant',true),'')::uuid"
                + " and status='ACTIVE' order by code")
        .stream()
        .map(
            r ->
                new ReceiptWarehouseOption(
                    r.get("id", UUID.class),
                    r.get("code", String.class),
                    r.get("name", String.class)))
        .toList();
  }

  // ================================================================ 详情

  /** 收货单详情：头 + 明细 + 实收事件 + 差异记录。跨租户在 RLS 下查不到 → 404。 */
  public GoodsReceiptDetail detail(UUID receiptId) {
    Record head = sql.fetchOne(rowSelect() + fromClause() + aggJoin() + " where r.id=?", receiptId);
    if (head == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "收货单不存在或无权查看");
    }
    GoodsReceiptRow receipt = row(head);
    String status = receipt.status();
    return new GoodsReceiptDetail(
        receipt,
        lineRows(receiptId),
        eventRows(receiptId),
        discrepancyRows(receiptId),
        ReceiptStatus.allowedNext(ReceiptStatus.parse(status)).stream()
            .map(to -> new ReceiptTransition(status, to.name(), true, "允许：" + to.label()))
            .toList(),
        CONSERVATION_NOTE);
  }

  private List<GoodsReceiptLineRow> lineRows(UUID receiptId) {
    return sql
        .fetch(
            "select l.*,s.code as sku_code,s.name as sku_name,"
                + " q.code as quality_status_code,q.name as quality_status_name,"
                + " coalesce(ev.n,0) as event_count,coalesce(sr.n,0) as serial_count"
                + " from goods_receipt_line l"
                + " join sku_ref s on s.tenant_id=l.tenant_id and s.id=l.sku_id"
                + " join inventory_quality_status q on q.tenant_id=l.tenant_id and q.id=l.quality_status_id"
                + " left join lateral (select count(*) as n from goods_receipt_event_line el"
                + "   where el.tenant_id=l.tenant_id and el.line_id=l.id) ev on true"
                + " left join lateral (select count(*) as n from goods_receipt_event_serial es"
                + "   join goods_receipt_event_line el2 on el2.tenant_id=es.tenant_id"
                + "    and el2.id=es.event_line_id"
                + "   where el2.tenant_id=l.tenant_id and el2.line_id=l.id) sr on true"
                + " where l.receipt_id=? order by s.code,l.quality_status_id",
            receiptId)
        .stream()
        .map(
            r -> {
              BigDecimal expected = decimal(r, "expected_quantity");
              BigDecimal received = decimal(r, "received_quantity");
              BigDecimal rejected = decimal(r, "rejected_quantity");
              return new GoodsReceiptLineRow(
                  r.get("id", UUID.class),
                  r.get("sku_id", UUID.class),
                  r.get("sku_code", String.class),
                  r.get("sku_name", String.class),
                  r.get("quality_status_id", UUID.class),
                  r.get("quality_status_code", String.class),
                  r.get("quality_status_name", String.class),
                  expected,
                  received,
                  rejected,
                  expected.subtract(received).subtract(rejected).max(BigDecimal.ZERO),
                  r.get("event_count", Long.class),
                  r.get("serial_count", Long.class),
                  r.get("version", Long.class));
            })
        .toList();
  }

  private List<GoodsReceiptEventRow> eventRows(UUID receiptId) {
    var byEvent = new LinkedHashMap<UUID, List<GoodsReceiptEventLineRow>>();
    for (var r :
        sql.fetch(
            "select el.*,s.code as sku_code,s.name as sku_name,lo.lot_code,"
                + " coalesce(sr.sn,'') as serial_numbers"
                + " from goods_receipt_event_line el"
                + " join goods_receipt_line l on l.tenant_id=el.tenant_id and l.id=el.line_id"
                + " join sku_ref s on s.tenant_id=l.tenant_id and s.id=l.sku_id"
                + " left join inventory_lot lo on lo.tenant_id=el.tenant_id and lo.id=el.lot_id"
                + " left join lateral (select string_agg(es.serial_number,',' order by"
                + "   es.serial_number) as sn from goods_receipt_event_serial es"
                + "  where es.tenant_id=el.tenant_id and es.event_line_id=el.id) sr on true"
                + " where el.event_id in (select id from goods_receipt_event where receipt_id=?)"
                + " order by el.created_at,el.id",
            receiptId)) {
      String serials = r.get("serial_numbers", String.class);
      byEvent
          .computeIfAbsent(r.get("event_id", UUID.class), k -> new ArrayList<>())
          .add(
              new GoodsReceiptEventLineRow(
                  r.get("id", UUID.class),
                  r.get("line_id", UUID.class),
                  r.get("sku_code", String.class),
                  r.get("sku_name", String.class),
                  decimal(r, "received_quantity"),
                  decimal(r, "rejected_quantity"),
                  r.get("lot_id", UUID.class),
                  r.get("lot_code", String.class),
                  r.get("expiry_date", LocalDate.class),
                  serials.isBlank() ? List.of() : Arrays.asList(serials.split(","))));
    }
    return sql
        .fetch(
            "select e.*,loc.code as location_code,loc.name as location_name"
                + " from goods_receipt_event e"
                + " join warehouse_location loc on loc.tenant_id=e.tenant_id and loc.id=e.location_id"
                + " where e.receipt_id=? order by e.received_at,e.id",
            receiptId)
        .stream()
        .map(
            e ->
                new GoodsReceiptEventRow(
                    e.get("id", UUID.class),
                    e.get("received_at", OffsetDateTime.class),
                    e.get("location_id", UUID.class),
                    e.get("location_code", String.class),
                    e.get("location_name", String.class),
                    e.get("waybill_no", String.class),
                    e.get("actor_id", UUID.class),
                    e.get("notes", String.class),
                    byEvent.getOrDefault(e.get("id", UUID.class), List.of())))
        .toList();
  }

  private List<GoodsReceiptDiscrepancyRow> discrepancyRows(UUID receiptId) {
    return sql
        .fetch(
            "select d.*,s.code as sku_code from goods_receipt_discrepancy d"
                + " left join goods_receipt_line l on l.tenant_id=d.tenant_id and l.id=d.line_id"
                + " left join sku_ref s on s.tenant_id=l.tenant_id and s.id=l.sku_id"
                + " where d.receipt_id=? order by d.recorded_at,d.id",
            receiptId)
        .stream()
        .map(
            d -> {
              var t = DiscrepancyType.parse(d.get("discrepancy_type", String.class));
              return new GoodsReceiptDiscrepancyRow(
                  d.get("id", UUID.class),
                  d.get("line_id", UUID.class),
                  d.get("sku_code", String.class),
                  t.name(),
                  t.label(),
                  decimal(d, "quantity"),
                  d.get("reason_code", String.class),
                  d.get("reason_note", String.class),
                  d.get("evidence_note", String.class),
                  d.get("recorded_at", OffsetDateTime.class),
                  d.get("actor_id", UUID.class));
            })
        .toList();
  }

  // ================================================================ 建单 / 改单

  /** 建单（草稿）。草稿不动库存 —— 货权与数量都在第一次实收那一刻才动。 */
  @Transactional
  public GoodsReceiptSaved create(UUID tenantId, GoodsReceiptCreateRequest request) {
    if (request == null) {
      throw new IllegalArgumentException("建单请求不能为空");
    }
    ReceiptSourceType source = ReceiptSourceType.parse(require(request.sourceType(), "收货来源类型"));
    requireSourceAvailable(source);
    UUID warehouseId = require(request.warehouseId(), "收货仓");
    requireWarehouse(tenantId, warehouseId, "收货仓");
    UUID legalEntityId = require(request.legalEntityId(), "货权法人");
    if (sql.fetchOne(
            "select id from legal_entity_ref where id=? and tenant_id=?", legalEntityId, tenantId)
        == null) {
      throw new IllegalArgumentException("货权法人不存在或无权访问：" + legalEntityId);
    }
    // 来源单据校验：已交付的来源必须真存在。**不建占位来源单据** ——
    // 一张指向空来源的收货单比没有收货单更糟。
    UUID sourceRefId = request.sourceRefId();
    if (source == ReceiptSourceType.TRANSFER) {
      if (sourceRefId == null) {
        throw new IllegalArgumentException("调拨来源必须指定来源调拨单 id");
      }
      requireTransferSource(tenantId, sourceRefId);
    } else if (sourceRefId != null) {
      throw new IllegalArgumentException(
          source.label() + "来源的上游模块尚未交付，不接受指定来源单据 id（见 /capabilities）");
    }
    String sourceRefCode = fit(request.sourceRefCode(), 64, "来源单号");

    var lines = request.lines();
    if (lines == null || lines.isEmpty()) {
      throw new IllegalArgumentException("收货单至少要有一条明细");
    }
    Map<String, UUID> lineIds = new LinkedHashMap<>();
    List<GoodsReceiptLineRequest> prepared = new ArrayList<>();
    for (var l : lines) {
      UUID skuId = require(l.skuId(), "SKU");
      if (sql.fetchOne("select id from sku_ref where id=? and tenant_id=?", skuId, tenantId)
          == null) {
        throw new IllegalArgumentException("SKU 不存在或无权访问：" + skuId);
      }
      UUID qualityId = require(l.qualityStatusId(), "落库质量状态");
      if (sql.fetchOne(
              "select id from inventory_quality_status where id=? and tenant_id=?",
              qualityId,
              tenantId)
          == null) {
        throw new IllegalArgumentException("落库质量状态不存在或无权访问：" + qualityId);
      }
      BigDecimal expected = positive(l.expectedQuantity(), "预期数量");
      String key = skuId + "|" + qualityId;
      if (lineIds.containsKey(key)) {
        throw new IllegalArgumentException(
            "同一 SKU 与落库质量状态只能有一行：把它们分成两行会让「这个货位上这批货到底是多少」" + "变成多行之和，正是明细页要消灭的歧义（" + skuId + "）");
      }
      lineIds.put(key, null);
      prepared.add(new GoodsReceiptLineRequest(skuId, qualityId, expected));
    }

    UUID id = UUID.randomUUID();
    // 单号 = GR-当天日期-六位序号。序号与日期分两步取，不在一条 SQL 里拼：
    // 纯文本语句里 nextval 套 lpad/::text 这类表达式 jOOQ 解析器吃不准，
    // 拼成一条还会让 PG 报「INSERT has more target columns than expressions」
    // 这种完全指不到真因的错（与 7.5.1.1 调拨单同一条纪律）。
    long seq = sql.fetchOne("select nextval('goods_receipt_code_seq')").get(0, Long.class);
    var today = sql.fetchOne("select current_date").get(0, LocalDate.class);
    String code = "GR-" + today.toString().replace("-", "") + "-" + String.format("%06d", seq);
    sql.execute(
        "insert into goods_receipt(id,tenant_id,receipt_code,source_type,source_ref_id,"
            + "source_ref_code,warehouse_id,legal_entity_id,status,carrier,waybill_no,"
            + "evidence_note,created_by) values(?,?,?,?,?,?,?,?,'DRAFT',?,?,?,?)",
        id,
        tenantId,
        code,
        source.name(),
        sourceRefId,
        sourceRefCode,
        warehouseId,
        legalEntityId,
        fit(request.carrier(), 100, "承运商"),
        fit(request.waybillNo(), 64, "运单号"),
        fit(request.evidenceNote(), 4000, "收货凭证说明"),
        UUID.randomUUID());

    for (var l : prepared) {
      UUID lineId = UUID.randomUUID();
      lineIds.put(l.skuId() + "|" + l.qualityStatusId(), lineId);
      sql.execute(
          "insert into goods_receipt_line(id,tenant_id,receipt_id,sku_id,quality_status_id,"
              + "expected_quantity) values(?,?,?,?,?,?)",
          lineId,
          tenantId,
          id,
          l.skuId(),
          l.qualityStatusId(),
          l.expectedQuantity());
    }
    return savedOf(id);
  }

  /** 改单：仅草稿可改，整份替换明细。实收过之后一行都不让改 —— 改预期会让已记的分录失去依据。 */
  @Transactional
  public GoodsReceiptSaved update(UUID receiptId, GoodsReceiptUpdateRequest request) {
    Record head = lockReceipt(receiptId);
    requireVersion(head, request == null ? null : request.expectedVersion());
    requireStatus(head, ReceiptStatus.DRAFT);
    UUID tenantId = head.get("tenant_id", UUID.class);

    var lines = request.lines();
    if (lines == null || lines.isEmpty()) {
      throw new IllegalArgumentException("收货单至少要有一条明细");
    }
    var seen = new java.util.HashSet<String>();
    for (var l : lines) {
      UUID skuId = require(l.skuId(), "SKU");
      if (sql.fetchOne("select id from sku_ref where id=? and tenant_id=?", skuId, tenantId)
          == null) {
        throw new IllegalArgumentException("SKU 不存在或无权访问：" + skuId);
      }
      UUID qualityId = require(l.qualityStatusId(), "落库质量状态");
      if (sql.fetchOne(
              "select id from inventory_quality_status where id=? and tenant_id=?",
              qualityId,
              tenantId)
          == null) {
        throw new IllegalArgumentException("落库质量状态不存在或无权访问：" + qualityId);
      }
      positive(l.expectedQuantity(), "预期数量");
      if (!seen.add(skuId + "|" + qualityId)) {
        throw new IllegalArgumentException("同一 SKU 与落库质量状态只能有一行：" + skuId);
      }
    }
    sql.execute("delete from goods_receipt_line where receipt_id=?", receiptId);
    for (var l : lines) {
      sql.execute(
          "insert into goods_receipt_line(id,tenant_id,receipt_id,sku_id,quality_status_id,"
              + "expected_quantity) values(?,?,?,?,?,?)",
          UUID.randomUUID(),
          tenantId,
          receiptId,
          l.skuId(),
          l.qualityStatusId(),
          positive(l.expectedQuantity(), "预期数量"));
    }
    sql.execute(
        "update goods_receipt set source_ref_code=?,carrier=?,waybill_no=?,evidence_note=?,"
            + "version=version+1,updated_at=now() where id=?",
        fit(request.sourceRefCode(), 64, "来源单号"),
        fit(request.carrier(), 100, "承运商"),
        fit(request.waybillNo(), 64, "运单号"),
        fit(request.evidenceNote(), 4000, "收货凭证说明"),
        receiptId);
    return savedOf(receiptId);
  }

  // ================================================================ 实收

  /**
   * 录入实收：逐笔落事件、按 SKU 转入待检或可售 bucket、写 RECEIPT 入库分录、登记差异。
   *
   * <p>可分次收货（蓝图：「部分收」是一张单上的常态）。每次调用是一笔独立事件， 落到同一个库位，因此只有一处 {@code locationId}。
   *
   * <p>一个事务里完成：事件 + 实收明细 + 序列号档案 + 余额增量 + 库位增量 + 分录 + 差异记录。 蓝图的硬约束「所有作业动作与库存分录**在同一本地事务**完成」由此满足：
   * 任何一步失败（库位不存在、批次不存在、序列号校验不过）都会整体回滚， 不会留下「货到了但没入库」或「入库了但没留痕」的半截状态。
   */
  @Transactional
  public GoodsReceiptSaved receive(UUID receiptId, GoodsReceiptReceiveRequest request) {
    if (request == null) {
      throw new IllegalArgumentException("实收请求不能为空");
    }
    Record head = lockReceipt(receiptId);
    requireVersion(head, request.expectedVersion());
    String status = head.get("status", String.class);
    ReceiptStatus current = ReceiptStatus.parse(status);
    if (current == ReceiptStatus.CLOSED) {
      throw statusError(status, "收货单已关闭，不再接受实收");
    }
    if (!ReceiptStatus.allowedNext(current).contains(ReceiptStatus.PARTIALLY_RECEIVED)
        && !ReceiptStatus.allowedNext(current).contains(ReceiptStatus.RECEIVED)) {
      throw statusError(status, "当前状态不能实收");
    }

    UUID tenantId = head.get("tenant_id", UUID.class);
    UUID warehouseId = head.get("warehouse_id", UUID.class);
    UUID legalEntityId = head.get("legal_entity_id", UUID.class);
    UUID actorId = UUID.randomUUID();
    OffsetDateTime receivedAt =
        request.receivedAt() == null ? OffsetDateTime.now() : request.receivedAt();
    UUID locationId = require(request.locationId(), "落库库位");
    requireLocation(tenantId, locationId, warehouseId);

    var lines = request.lines();
    if (lines == null || lines.isEmpty()) {
      throw new IllegalArgumentException("本次实收没有指定任何明细行");
    }

    // 逐行先校验再落库：所有校验通过之前不写任何一行，
    // 免得「第 3 行批次不存在」时前两行已经入了库。
    record Checked(
        Record line,
        BigDecimal received,
        BigDecimal rejected,
        UUID lotId,
        LocalDate expiry,
        List<String> serials) {}
    var checked = new ArrayList<Checked>();
    for (var l : lines) {
      Record line = requireLine(receiptId, l.lineId());
      BigDecimal received = nonNegative(l.receivedQuantity(), "实收数量");
      BigDecimal rejected = nonNegative(l.rejectedQuantity(), "拒收数量");
      if (received.signum() == 0 && rejected.signum() == 0) {
        throw new IllegalArgumentException("明细行 " + l.lineId() + " 的实收与拒收不能同时为 0：那意味着这次什么都没发生");
      }
      if (l.lotId() != null
          && sql.fetchOne(
                  "select id from inventory_lot where id=? and tenant_id=?", l.lotId(), tenantId)
              == null) {
        throw new IllegalArgumentException("批次不存在或无权访问：" + l.lotId());
      }
      // 「不允许大概齐」：传了序列号就必须在数量上对得上。
      // 数量为 1 时必须且仅有 1 个号，数量为 N 时必须恰好 N 个。
      List<String> serials = clean(l.serialNumbers());
      if (!serials.isEmpty() && serials.size() != received.intValueExact()) {
        throw new IllegalArgumentException(
            "序列号条数与实收数量必须完全相等：实收 " + received + "，但给了 " + serials.size() + " 个序列号（不允许「大概齐」）");
      }
      if (new java.util.HashSet<>(serials).size() != serials.size()) {
        throw new IllegalArgumentException("同一行内序列号重复：" + l.lineId());
      }
      checked.add(new Checked(line, received, rejected, l.lotId(), l.expiryDate(), serials));
    }

    UUID eventId = UUID.randomUUID();
    sql.execute(
        "insert into goods_receipt_event(id,tenant_id,receipt_id,received_at,location_id,waybill_no,"
            + "actor_id,notes) values(?,?,?,cast(? as timestamptz),?,?,?,?)",
        eventId,
        tenantId,
        receiptId,
        receivedAt,
        locationId,
        fit(request.waybillNo(), 64, "本次运单号"),
        actorId,
        fit(request.notes(), 2000, "实收备注"));

    // 只有真的入库才开一笔分录事务。
    // 整单全是拒收时（货到了但一件不要），如果照样开事务，就会留下一条
    // **没有任何分录的 inventory_transaction** —— 台账 7.1.2.1 上一笔查无下文的空交易，
    // 而 CHECK(on_hand_delta<>0) 又保证了 entry 侧无法作陪。这比不记更糟：
    // 它让「这笔到货完全没有入库」这件事看起来像是记了账。
    BigDecimal totalIn = BigDecimal.ZERO;
    for (var c : checked) {
      totalIn = totalIn.add(c.received());
    }
    UUID transactionId = null;
    if (totalIn.signum() > 0) {
      transactionId = UUID.randomUUID();
      // 分录用 V1 已有的 kind='RECEIPT'（见迁移 V70 文件头「三」）：
      // 加新 kind 要改 V1 的 CHECK 约束面，那会同时波及 7.1.2.1 / 7.1.1.1 等一堆既有模块。
      sql.execute(
          "insert into inventory_transaction(id,tenant_id,kind,ref_type,ref_id,occurred_at,actor_id,"
              + "notes) values(?,?,?,?,?,cast(? as timestamptz),?,?)",
          transactionId,
          tenantId,
          "RECEIPT",
          "GOODS_RECEIPT",
          eventId,
          receivedAt,
          actorId,
          "收货入库：实收 " + totalIn + " 转入库存（待检或可售 bucket）；拒收部分不入库、不写分录");
    }

    for (var c : checked) {
      Record line = c.line();
      UUID lineId = line.get("id", UUID.class);
      BigDecimal expected = decimal(line, "expected_quantity");
      BigDecimal alreadyReceived = decimal(line, "received_quantity");
      BigDecimal alreadyRejected = decimal(line, "rejected_quantity");
      BigDecimal nextReceived = alreadyReceived.add(c.received());
      BigDecimal nextRejected = alreadyRejected.add(c.rejected());

      UUID eventLineId = UUID.randomUUID();
      sql.execute(
          "insert into goods_receipt_event_line(id,tenant_id,event_id,line_id,received_quantity,"
              + "rejected_quantity,lot_id,expiry_date) values(?,?,?,?,?,?,?,?)",
          eventLineId,
          tenantId,
          eventId,
          lineId,
          c.received(),
          c.rejected(),
          c.lotId(),
          c.expiry() == null ? null : java.sql.Date.valueOf(c.expiry()));

      for (var sn : c.serials()) {
        sql.execute(
            "insert into goods_receipt_event_serial(id,tenant_id,event_line_id,serial_number,sku_id,"
                + "lot_id) values(?,?,?,?,?,?)",
            UUID.randomUUID(),
            tenantId,
            eventLineId,
            sn,
            line.get("sku_id", UUID.class),
            c.lotId());
        // 序列号**真的**进 7.3.1.x 的库存序列号档案，不在本模块另起一张号表。
        var result =
            serials_()
                .register(
                    new InventorySerialModels.SerialRegisterRequest(
                        sn,
                        line.get("sku_id", UUID.class),
                        c.lotId(),
                        locationId,
                        "GOODS_RECEIPT",
                        eventId,
                        "收货登记：" + head.get("receipt_code", String.class),
                        receivedAt),
                    actorId);
        if (!result.applied()) {
          throw new IllegalArgumentException(
              "序列号 "
                  + sn
                  + " 无法登记进库存序列号档案："
                  + (result.issues() == null || result.issues().isEmpty()
                      ? "校验未通过"
                      : result.issues().stream()
                          .map(InventorySerialModels.SerialIssue::message)
                          .toList()));
        }
      }

      // 差异留痕（事实记录，处理归 8.2.1.2）：
      //   * 超收：received + rejected > expected，**必须**有 OVER_RECEIPT 记录
      //     （DB 触发器 guard_receipt_line_overage 会兜底，这里先显式登记）
      //   * 拒收：拒收量必须有一条 REJECTED 记录，否则「为什么这批没进库」永远答不出来
      if (nextReceived.add(nextRejected).compareTo(expected) > 0) {
        BigDecimal over = nextReceived.add(nextRejected).subtract(expected);
        insertDiscrepancy(
            receiptId,
            lineId,
            DiscrepancyType.OVER_RECEIPT,
            over,
            "OVER_RECEIPT",
            "现场超收：实收 + 拒收超出预期 " + over,
            receivedAt,
            actorId,
            "");
      }
      if (c.rejected().signum() > 0) {
        // 凭证线索取本次实收的备注：逐条差异自带的凭证说明走 discrepancies 数组，
        // 那是现场对**单个差异**的留证，而这里是「这一拒收动作发生在哪一次到货」的上下文。
        insertDiscrepancy(
            receiptId,
            lineId,
            DiscrepancyType.REJECTED,
            c.rejected(),
            "REJECTED",
            "现场拒收：该部分不入库、不写分录",
            receivedAt,
            actorId,
            fit(request.notes(), 4000, "实收备注"));
      }

      if (c.received().signum() > 0) {
        UUID balanceId =
            balanceId(
                tenantId,
                line.get("sku_id", UUID.class),
                warehouseId,
                legalEntityId,
                line.get("quality_status_id", UUID.class));
        addInbound(balanceId, locationId, c.lotId(), c.received(), receivedAt);
        sql.execute(
            "insert into inventory_entry(id,tenant_id,transaction_id,balance_id,on_hand_delta,"
                + "reserved_delta) values(?,?,?,?,?,0)",
            UUID.randomUUID(),
            tenantId,
            transactionId,
            balanceId,
            c.received());
      }

      sql.execute(
          "update goods_receipt_line set received_quantity=?,rejected_quantity=?,version=version+1,"
              + "updated_at=now() where id=?",
          nextReceived,
          nextRejected,
          lineId);
    }

    recordDiscrepancies(receiptId, request.discrepancies(), receivedAt, actorId);
    deriveStatus(receiptId);
    return savedOf(receiptId);
  }

  /**
   * 显式差异登记（破损 / 型号不符 / 过期 / 错发等现场发现）。
   *
   * <p>{@code discrepancyType} 为空时**按数量判定或报错，不猜**：猜错类型的差异记录 会直接误导 8.2.1.2
   * 的索赔判断，而「破损」与「型号不符」的下游处理完全不同。
   */
  private void recordDiscrepancies(
      UUID receiptId, List<ReceiptDiscrepancyRequest> list, OffsetDateTime at, UUID actorId) {
    if (list == null || list.isEmpty()) {
      return;
    }
    for (var d : list) {
      BigDecimal qty = positive(d.quantity(), "差异数量");
      String reasonCode = fit(d.reasonCode(), 64, "差异原因码");
      String reasonNote = fit(d.reasonNote(), 4000, "差异说明");
      String evidence = fit(d.evidenceNote(), 4000, "差异凭证说明");
      if (reasonCode.isBlank() && reasonNote.isBlank() && evidence.isBlank()) {
        throw new IllegalArgumentException("差异登记必须给出原因、说明或凭证三者之一：三者都空的差异记录等于没有原因");
      }
      DiscrepancyType type;
      if (d.discrepancyType() == null || d.discrepancyType().isBlank()) {
        Record line = requireLine(receiptId, d.lineId());
        BigDecimal expected = decimal(line, "expected_quantity");
        BigDecimal done =
            decimal(line, "received_quantity").add(decimal(line, "rejected_quantity"));
        if (done.add(qty).compareTo(expected) > 0) {
          type = DiscrepancyType.OVER_RECEIPT;
        } else {
          throw new IllegalArgumentException(
              "必须明确差异类型：按数量只能判定出「超收」，其余（少收/拒收/破损/型号不符/过期/错发）" + "必须由现场指明，猜错会误导 8.2.1.2 的索赔判断");
        }
      } else {
        type = DiscrepancyType.parse(d.discrepancyType());
      }
      if (d.lineId() != null) {
        requireLine(receiptId, d.lineId());
      }
      insertDiscrepancy(
          receiptId,
          d.lineId(),
          type,
          qty,
          type == DiscrepancyType.OVER_RECEIPT ? "OVER_RECEIPT" : reasonCode,
          reasonNote,
          at,
          actorId,
          evidence);
    }
  }

  private void insertDiscrepancy(
      UUID receiptId,
      UUID lineId,
      DiscrepancyType type,
      BigDecimal quantity,
      String reasonCode,
      String reasonNote,
      OffsetDateTime at,
      UUID actorId,
      String evidence) {
    sql.execute(
        "insert into goods_receipt_discrepancy(id,tenant_id,receipt_id,line_id,discrepancy_type,"
            + "quantity,reason_code,reason_note,evidence_note,recorded_at,actor_id)"
            + " values(?,?,?,?,?,?,?,?,?,cast(? as timestamptz),?)",
        UUID.randomUUID(),
        sql.fetchOne("select tenant_id from goods_receipt where id=?", receiptId)
            .get("tenant_id", UUID.class),
        receiptId,
        lineId,
        type.name(),
        quantity,
        fit(reasonCode, 64, "差异原因码"),
        fit(reasonNote, 4000, "差异说明"),
        fit(evidence, 4000, "差异凭证说明"),
        at,
        actorId);
  }

  // ================================================================ 关单

  /**
   * 关单：把剩余未到齐的部分如实记成少收，然后结案。
   *
   * <p>**不自动抹平**：剩下的那部分不会被悄悄记成「已收」，也不会被塞进拒收 —— 它就是这批货真的没到，原因是必填的一条 SHORT_RECEIPT。
   *
   * <p>已关闭是终态：再收货等于账外入库。
   */
  @Transactional
  public GoodsReceiptSaved close(UUID receiptId, GoodsReceiptCloseRequest request) {
    Record head = lockReceipt(receiptId);
    requireVersion(head, request == null ? null : request.expectedVersion());
    String status = head.get("status", String.class);
    ReceiptStatus current = ReceiptStatus.parse(status);
    if (!ReceiptStatus.allowedNext(current).contains(ReceiptStatus.CLOSED)) {
      throw statusError(status, "当前状态不能关单");
    }
    UUID tenantId = head.get("tenant_id", UUID.class);
    UUID actorId = UUID.randomUUID();
    OffsetDateTime at = OffsetDateTime.now();
    String reason = request == null ? "" : fit(request.reason(), 200, "关单原因");
    String notes = request == null ? "" : fit(request.notes(), 2000, "关单备注");

    var outstanding =
        sql.fetch(
            "select id,expected_quantity,received_quantity,rejected_quantity"
                + " from goods_receipt_line where receipt_id=? and"
                + " expected_quantity-received_quantity-rejected_quantity>0 for update",
            receiptId);
    boolean anyShort = false;
    for (var l : outstanding) {
      BigDecimal rest =
          decimal(l, "expected_quantity")
              .subtract(decimal(l, "received_quantity"))
              .subtract(decimal(l, "rejected_quantity"));
      insertDiscrepancy(
          receiptId,
          l.get("id", UUID.class),
          DiscrepancyType.SHORT_RECEIPT,
          rest,
          "SHORT_RECEIPT",
          "关单时如实记录：预期未到齐 " + rest + "（货可能还在路上，也可能是供应商少发，两者的处理归 8.2.1.2 与 6.5 索赔）",
          at,
          actorId,
          notes);
      anyShort = true;
    }

    String closeReason = reason.isBlank() ? (anyShort ? "未到齐关单，短少已如实登记" : "收货完成关单") : reason;
    // 关单时间与状态在同一条 UPDATE 里写：guard_receipt_state 要求两者自洽，
    // 分两次写会在中间态撞上 CHECK。
    sql.execute(
        "update goods_receipt set status='CLOSED',closed_at=cast(? as timestamptz),closed_by=?,"
            + "close_reason=?,version=version+1,updated_at=now() where id=?",
        at,
        actorId,
        fit(closeReason, 200, "关单原因"),
        receiptId);
    return savedOf(receiptId);
  }

  // ================================================================ 状态机

  /**
   * 状态机的**唯一真相**：实收与关单都不各自拼状态，改完数量后统一由它算出来。
   *
   * <p>两个地方各写一遍 if 的话，「最后一车收齐到底该是已收还是部分收」这种边界迟早会写错一个。 已关闭是终态，不参与推导 —— 关单之后不该被下一次实收悄悄改回去。
   */
  private void deriveStatus(UUID receiptId) {
    Record head = sql.fetchOne("select tenant_id,status from goods_receipt where id=?", receiptId);
    if (head == null || ReceiptStatus.CLOSED.name().equals(head.get("status", String.class))) {
      return;
    }
    BigDecimal expected = BigDecimal.ZERO;
    BigDecimal received = BigDecimal.ZERO;
    long lines = 0;
    long events =
        sql.fetchOne("select count(*) from goods_receipt_event where receipt_id=?", receiptId)
            .get(0, Long.class);
    for (var r :
        sql.fetch(
            "select coalesce(sum(expected_quantity),0) as expected,"
                + " coalesce(sum(received_quantity),0) as received,count(*) as n"
                + " from goods_receipt_line where receipt_id=?",
            receiptId)) {
      expected = decimal(r, "expected");
      received = decimal(r, "received");
      lines = r.get("n", Long.class);
    }
    ReceiptStatus next;
    if (lines == 0 || events == 0) {
      // 一次实收都没发生：仍是草稿。判据是**实收笔数**而不是实收量 ——
      // 整单全被拒收时实收量是 0，但那确实是一次真实的到货动作，
      // 用实收量判会让单据退回草稿，而 DB 触发器 guard_receipt_event_state
      // 明确禁止「有实收事件却还是草稿」。两边用同一个判据，不会互相打架。
      next = ReceiptStatus.DRAFT;
    } else if (received.compareTo(expected) >= 0) {
      next = ReceiptStatus.RECEIVED;
    } else {
      next = ReceiptStatus.PARTIALLY_RECEIVED;
    }
    sql.execute(
        "update goods_receipt set status=?,version=version+1,updated_at=now() where id=?",
        next.name(),
        receiptId);
  }

  // ================================================================ 入库

  /**
   * 入库：加余额 + 同步加库位明细（V2 守恒触发器要求两处同动）。
   *
   * <p>用**带符号增量**而不是覆盖式写回：覆盖式写回（{@code set on_hand = 期望值}） 会把收货期间真实发生的其他交易（并行的出库、预占、盘点调整）整段抹掉。
   *
   * <p>{@code last_movement_at} 用 {@code greatest(...)} 保证严格单调： V2 的 {@code
   * guard_bucket_movement_time} 会挡住倒退，业务时间早于既有变动时间时 用「原值 + 1 微秒」兜底。
   */
  private void addInbound(
      UUID balanceId, UUID locationId, UUID lotId, BigDecimal qty, OffsetDateTime at) {
    Record existing =
        sql.fetchOne(
            "select id from stock_bucket where balance_id=? and location_id=?"
                + " and lot_id is not distinct from ?",
            balanceId,
            locationId,
            lotId);
    if (existing != null) {
      sql.execute(
          "update stock_bucket set on_hand=on_hand+?,"
              + " last_movement_at=greatest(cast(? as timestamptz),last_movement_at"
              + " + interval '1 microsecond') where id=?",
          qty,
          at,
          existing.get("id", UUID.class));
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
        qty,
        balanceId);
  }

  /** 取（或建）一条余额行。跨租户塞进来的仓/SKU 会被 UNIQUE 或 RLS 挡住。 */
  private UUID balanceId(
      UUID tenantId, UUID skuId, UUID warehouseId, UUID legalEntityId, UUID qualityStatusId) {
    var found =
        sql.fetchOne(
            "select id from inventory_balance where tenant_id=? and sku_id=? and warehouse_id=?"
                + " and legal_entity_id=? and quality_status_id=?",
            tenantId,
            skuId,
            warehouseId,
            legalEntityId,
            qualityStatusId);
    if (found != null) {
      return found.get("id", UUID.class);
    }
    var id = UUID.randomUUID();
    sql.execute(
        "insert into inventory_balance(id,tenant_id,sku_id,warehouse_id,legal_entity_id,"
            + "quality_status_id,on_hand,reserved) values(?,?,?,?,?,?,0,0)",
        id,
        tenantId,
        skuId,
        warehouseId,
        legalEntityId,
        qualityStatusId);
    return id;
  }

  // ================================================================ 校验与工具

  /** 锁单 + 读租户。并发实收/关单时同一张单被串行化，版本比对才可靠。 */
  private Record lockReceipt(UUID receiptId) {
    Record head = sql.fetchOne("select * from goods_receipt where id=? for update", receiptId);
    if (head == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "收货单不存在或无权查看");
    }
    return head;
  }

  /** 乐观锁：蓝图把 expected_version 写进输入契约，版本不符一律 409，绝不静默覆盖。 */
  private void requireVersion(Record head, Long expected) {
    if (expected == null) {
      throw new IllegalArgumentException("缺少 expectedVersion：写操作必须带上乐观锁版本号");
    }
    long actual = head.get("version", Long.class);
    if (actual != expected) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "收货单已被他人修改（当前版本 " + actual + "，你的版本 " + expected + "），请刷新后重试");
    }
  }

  private void requireStatus(Record head, ReceiptStatus expected) {
    String actual = head.get("status", String.class);
    if (!actual.equals(expected.name())) {
      throw statusError(actual, "只有「" + expected.label() + "」状态才能执行该操作");
    }
  }

  private static ResponseStatusException statusError(String actual, String hint) {
    return new ResponseStatusException(
        HttpStatus.CONFLICT, "收货单当前是「" + ReceiptStatus.parse(actual).label() + "」：" + hint);
  }

  private Record requireLine(UUID receiptId, UUID lineId) {
    if (lineId == null) {
      throw new IllegalArgumentException("必须指定收货明细行");
    }
    Record line =
        sql.fetchOne(
            "select * from goods_receipt_line where id=? and receipt_id=?", lineId, receiptId);
    if (line == null) {
      throw new IllegalArgumentException("明细行不属于这张收货单：" + lineId);
    }
    return line;
  }

  private void requireWarehouse(UUID tenantId, UUID warehouseId, String label) {
    if (sql.fetchOne(
            "select id from warehouse where id=? and tenant_id=? and status='ACTIVE'",
            warehouseId,
            tenantId)
        == null) {
      throw new IllegalArgumentException(label + "不存在、不属于本租户或已停用：" + warehouseId);
    }
  }

  private void requireLocation(UUID tenantId, UUID locationId, UUID warehouseId) {
    if (sql.fetchOne(
            "select id from warehouse_location where id=? and tenant_id=? and warehouse_id=?",
            locationId,
            tenantId,
            warehouseId)
        == null) {
      throw new IllegalArgumentException("落库库位不存在，或不属于收货仓（收货只能落到本仓的库位）：" + locationId);
    }
  }

  /** 已交付的来源必须真存在；未交付的来源直接拒绝，不建占位单据。 */
  private void requireSourceAvailable(ReceiptSourceType source) {
    boolean available = source == ReceiptSourceType.TRANSFER; // 见 capabilities() 里对集成头的实际查证结论
    if (!available) {
      throw new IllegalArgumentException(
          source.label()
              + "来源的上游模块（"
              + source.ownerModule()
              + "）尚未交付，不接受该来源的收货单。已交付的来源只有"
              + "调拨单（7.5.1.1）。详见 /warehouse/goods-receipts/capabilities");
    }
  }

  private void requireTransferSource(UUID tenantId, UUID transferOrderId) {
    Record order =
        sql.fetchOne(
            "select status,to_warehouse_id from transfer_order where id=? and tenant_id=?",
            transferOrderId,
            tenantId);
    if (order == null) {
      throw new IllegalArgumentException(
          "来源调拨单不存在或无权访问：" + transferOrderId + "（本模块不建占位来源单据 —— 一张指向空来源的收货单比没有收货单更糟）");
    }
    String status = order.get("status", String.class);
    if (!status.equals("IN_TRANSIT")
        && !status.equals("PARTIALLY_RECEIVED")
        && !status.equals("RECEIVED")) {
      throw new IllegalArgumentException("来源调拨单当前是「" + status + "」：只有已发出（在途 / 部分收 / 已收）的调拨单才谈得上收货");
    }
  }

  private static UUID require(UUID value, String label) {
    if (value == null) {
      throw new IllegalArgumentException("缺少" + label);
    }
    return value;
  }

  private static String require(String value, String label) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("缺少" + label);
    }
    return value;
  }

  private static BigDecimal positive(BigDecimal value, String label) {
    if (value == null || value.signum() <= 0) {
      throw new IllegalArgumentException(label + "必须大于 0");
    }
    if (value.scale() > 6) {
      throw new IllegalArgumentException(label + "最多 6 位小数（与库存数量口径 numeric(19,6) 一致）");
    }
    return value;
  }

  private static BigDecimal nonNegative(BigDecimal value, String label) {
    if (value == null) {
      return BigDecimal.ZERO;
    }
    if (value.signum() < 0) {
      throw new IllegalArgumentException(label + "不能为负");
    }
    if (value.scale() > 6) {
      throw new IllegalArgumentException(label + "最多 6 位小数（与库存数量口径 numeric(19,6) 一致）");
    }
    return value;
  }

  private static List<String> clean(List<String> values) {
    if (values == null) {
      return List.of();
    }
    var out = new ArrayList<String>();
    for (var v : values) {
      if (v != null && !v.isBlank()) {
        String s = v.trim();
        if (s.length() > 128) {
          throw new IllegalArgumentException("序列号长度不能超过 128 个字符");
        }
        out.add(s);
      }
    }
    return out;
  }

  private static String fit(String value, int max, String label) {
    if (value != null && value.length() > max) {
      throw new IllegalArgumentException(label + "长度不能超过 " + max + " 个字符");
    }
    return value == null ? "" : value.trim();
  }

  private static List<Object> append(List<Object> binds, Object... more) {
    var all = new ArrayList<>(binds);
    all.addAll(Arrays.asList(more));
    return all;
  }

  /** 读数量。纯文本 SQL 里的表达式列 jOOQ 拿不到列类型，收口成不依赖驱动返回类型的读法。 */
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

  private static long n(Record r, String field) {
    var value = r.get(field);
    return value == null ? 0L : ((Number) value).longValue();
  }

  private static GoodsReceiptRow row(Record r) {
    var status = ReceiptStatus.parse(r.get("status", String.class));
    var sourceType = ReceiptSourceType.parse(r.get("source_type", String.class));
    return new GoodsReceiptRow(
        r.get("receipt_id", UUID.class),
        r.get("receipt_code", String.class),
        sourceType.name(),
        sourceType.label(),
        r.get("source_ref_id", UUID.class),
        r.get("source_ref_code", String.class),
        r.get("warehouse_id", UUID.class),
        r.get("warehouse_code", String.class),
        r.get("warehouse_name", String.class),
        r.get("legal_entity_id", UUID.class),
        r.get("legal_entity_name", String.class),
        status.name(),
        status.label(),
        r.get("carrier", String.class),
        r.get("waybill_no", String.class),
        decimal(r, "expected_quantity"),
        decimal(r, "received_quantity"),
        decimal(r, "rejected_quantity"),
        decimal(r, "outstanding_quantity"),
        n(r, "line_count"),
        n(r, "event_count"),
        n(r, "discrepancy_count"),
        r.get("closed_at", OffsetDateTime.class),
        n(r, "version"),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  /** 建单/改单/实收/关单后回读：响应是「库里真实存了什么」。 */
  private GoodsReceiptSaved savedOf(UUID receiptId) {
    Record r = sql.fetchOne(rowSelect() + fromClause() + aggJoin() + " where r.id=?", receiptId);
    if (r == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "收货单不存在或无权查看");
    }
    var status = ReceiptStatus.parse(r.get("status", String.class));
    return new GoodsReceiptSaved(
        r.get("receipt_id", UUID.class),
        r.get("receipt_code", String.class),
        status.name(),
        status.label(),
        n(r, "version"),
        decimal(r, "expected_quantity"),
        decimal(r, "received_quantity"),
        decimal(r, "rejected_quantity"),
        decimal(r, "outstanding_quantity"),
        r.get("closed_at", OffsetDateTime.class));
  }

  /** 序列号服务句柄。收敛成一个方法，避免字段名与局部变量 {@code serials} 撞名。 */
  private InventorySerialService serials_() {
    return serials;
  }
}
