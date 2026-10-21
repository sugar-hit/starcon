package com.starcon.warehouse;

import static com.starcon.warehouse.PendingInspectionModels.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
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
 * 8.2.2.2 待检处理：待检区库存 → 质检 → 质量状态分流。
 *
 * <p>蓝图 08 卷 8.2.2.2：
 *
 * <ul>
 *   <li>职责：管理待检区库存：发起质检任务（6 质检单）、质检通过后转入可售、质检不通过转残次/报废/退货。
 *   <li>输入：待检库存清单；质检结果；处置决定。
 *   <li>输出：质量状态变更分录；可售量更新。
 *   <li>关系：上游 = 8.2.1.1、6.4.2.1；下游 = 7.1.1.3 可用量、6.4.2.2。
 *   <li><b>待检品不可售</b>是硬规则，质检通过前不能被销售预占。
 * </ul>
 *
 * <p><b>本模块在账上的形状</b>（V2 的分层决定的，不是本模块自己选的）：
 *
 * <ul>
 *   <li>质量状态是 balance / bucket 的一个<b>维度</b>，所以「转可售」不是改一列，
 *       而是<b>一对反向分录</b>：待检 balance −Q、可售 balance +Q，
 *       同时待检 bucket −Q（货离开待检库位）、可售 bucket +Q（货落到可拣库位）。
 *   <li>一次处置 = <b>一条</b> {@code inventory_transaction} + N 条分录。
 *       一张任务一条交易，于是 {@code UNIQUE(tenant_id,ref_type,ref_id)} 顺带给整张任务单幂等，
 *       而 V252 的守卫要核对的 P1/P2/P3 也只在单条交易内才说得清。
 *   <li>报废与退货是<b>离仓</b>：货不再在本仓，所以它们只有负向分录，没有正向分录。
 * </ul>
 *
 * <p><b>本模块不做的两件事</b>（各有明确的归属，不是省事）：
 *
 * <ul>
 *   <li><b>不建质检单表</b>：6.4.2.1 质检单归 trade 服务（V249），跨服务 FK / SQL 被 AGENTS.md 禁止。
 *       本服务经 {@link QualityInspectionTradePort} 调 trade 自己的授权端点。
 *   <li><b>不替 6.4.2.2 做不良品处置</b>：蓝图把「6.4.2.2 不良品处理」列为下游，
 *       本模块只把不合格量**分流出去**（转残次 / 报废 / 退货），
 *       后续的退换货补货 / 返修 / 索赔归 6.4.2.2。
 * </ul>
 */
@Service
public class PendingInspectionService {

  /** 单页最大行数。 */
  static final int MAX_PAGE_SIZE = 200;

  /** 待检清单排序键白名单。 */
  private static final List<String> STOCK_SORTS =
      List.of("ON_HAND", "SKU_CODE", "LOCATION_CODE", "LAST_MOVEMENT");

  /** 任务列表排序键白名单。 */
  private static final List<String> TASK_SORTS =
      List.of("CREATED_AT", "DISPOSED_AT", "QUANTITY", "TASK_CODE");

  /** 数量刻度：与 DB 的 numeric(19,6) 对齐。 */
  private static final int QUANTITY_SCALE = 6;

  /** 待检清单的固定 FROM。 */
  private static final String STOCK_FROM =
      """
      from stock_bucket b
      join sku_ref s on s.tenant_id=b.tenant_id and s.id=b.sku_id
      join warehouse w on w.tenant_id=b.tenant_id and w.id=b.warehouse_id
      join warehouse_location loc
        on loc.tenant_id=b.tenant_id and loc.warehouse_id=b.warehouse_id and loc.id=b.location_id
      left join inventory_lot lot on lot.tenant_id=b.tenant_id and lot.id=b.lot_id
      join inventory_quality_status q on q.tenant_id=b.tenant_id and q.id=b.quality_status_id
      """;

  /** 任务列表的固定 FROM。 */
  private static final String TASK_FROM =
      """
      from pending_inspection_task t
      join warehouse w on w.tenant_id=t.tenant_id and w.id=t.warehouse_id
      join sku_ref s on s.tenant_id=t.tenant_id and s.id=t.sku_id
      """;

  private final DSLContext sql;
  private final QualityInspectionTradePort trade;

  public PendingInspectionService(DSLContext sql, QualityInspectionTradePort trade) {
    this.sql = sql;
    this.trade = trade;
  }

  // ================================================================ 能力声明

  /**
   * 能力与上下游可用性。
   *
   * <p>存在的理由与既有 warehouse 模块一致：把「发起质检走哪条通道」「不合格品能不能转可售」
   * 「6.4.2.2 交付后谁接管」显式报出来，而不是让调用方从 400 或空数组里猜。
   */
  public PendingInspectionCapabilities capabilities() {
    var upstream =
        List.of(
            new PendingInspectionCapabilities.PendingInspectionUpstreamModule(
                "8.2.1.1",
                "收货单（warehouse 服务）",
                "warehouse",
                true,
                "待检库存的来源：待检处理单必须挂一张真实的收货单与收货明细行（真外键），"
                    + "待检量不得超过该行的实收量。"),
            new PendingInspectionCapabilities.PendingInspectionUpstreamModule(
                "6.4.2.1",
                "质检单（trade 服务）",
                "trade",
                true,
                "经 trade 自己的授权端点 POST /trade/quality-inspections 发起质检任务，"
                    + "GET /trade/quality-inspections/{id} 回读三量结论。"
                    + "质检单事实表归 trade 所有，本服务只留逻辑 ID 与单号快照，"
                    + "不在本地建质检单表、不跨库读写。"));
    var downstream =
        List.of(
            new PendingInspectionCapabilities.PendingInspectionDownstreamModule(
                "7.1.1.3",
                "可用量计算（warehouse 服务）",
                "warehouse",
                true,
                "待检品（role=PENDING_INSPECTION）本来就不在可售基数里；"
                    + "本模块处置成可售之后，可售量按同一口径现算即可看到变化 —— "
                    + "本模块在处置前后各记一次快照（sellableQuantityBefore / After）。"),
            new PendingInspectionCapabilities.PendingInspectionDownstreamModule(
                "6.4.2.2",
                "不良品处理（trade 服务）",
                "trade",
                false,
                "不合格品的退换货补货 / 返修 / 索赔归 6.4.2.2。"
                    + "本模块只把不合格量分流出去（转残次 / 报废 / 退货），"
                    + "不代它做处置流程，也不代它算赔款。"),
            new PendingInspectionCapabilities.PendingInspectionDownstreamModule(
                "8.1.2.2",
                "库位自动分配（warehouse 服务）",
                "warehouse",
                true,
                "目标库位类型由质量角色决定这条硬规则，本模块复用它的权威函数"
                    + "location_allocation_required_type(role)，不另抄一份映射。"));
    var rules =
        List.of(
            new PendingInspectionCapabilities.PendingInspectionHardRule(
                "PENDING_NOT_SELLABLE",
                "待检品不可售：质检通过前不进可售基数，也不许被销售预占。",
                "V1 guard_balance_reservable + V2 guard_bucket_reservable + 7.1.1.3 可售基数口径"),
            new PendingInspectionCapabilities.PendingInspectionHardRule(
                "DISPOSE_COVERS_WHOLE_LOT",
                "一批待检货必须被全部处置掉：各去向数量之和必须等于任务批量。",
                "服务层校验 + V252 guard_pending_inspection_task 的 P1"),
            new PendingInspectionCapabilities.PendingInspectionHardRule(
                "DEFECTIVE_NOT_SELLABLE",
                "不合格品不能转可售：转可售的量不得超过质检结论里的可售量（合格量 + 让步接收量）。",
                "服务层校验 + V252 guard_pending_inspection_disposition 的 4.2.4"),
            new PendingInspectionCapabilities.PendingInspectionHardRule(
                "LOCATION_MATCHES_QUALITY",
                "目标库位类型必须与质量角色匹配（可售→存储 / 残次→残次），且不允许混放。",
                "V252 guard_pending_inspection_disposition 复用 location_allocation_required_type"),
            new PendingInspectionCapabilities.PendingInspectionHardRule(
                "LEDGER_MATCHES_DOCUMENT",
                "处置明细与账本分录必须逐行对上（每个目标余额行的转入量 = 指向它的明细量之和）。",
                "V252 guard_pending_inspection_task 的 P3 / P4"));
    return new PendingInspectionCapabilities(
        "PENDING_INSPECTION",
        true,
        true,
        true,
        "待检库存的来源是 8.2.1.1 收货单；待检货此刻所在的库存行（库位 × 批次 × 质量状态）是处置的起点。",
        "质检单（6.4.2.1）归 trade 服务所有：本服务经它的授权端点发起与回读，不在本地建质检单表。",
        "处置只有四个去向：转可售 / 转残次 / 报废 / 退货。前两个留在本仓（改质量状态 + 换库位），"
            + "后两个离仓（只减量）。",
        upstream,
        downstream,
        rules,
        "本模块产出的两件事实：质量状态变更分录（inventory_transaction + inventory_entry，"
            + "ref_type=PENDING_INSPECTION）与可售量更新（处置前后快照 + 7.1.1.3 现算口径）。");
  }

  // ================================================================ 待检库存清单

  /**
   * 待检库存清单。
   *
   * <p>只列 role='PENDING_INSPECTION' 的库存行 —— 这就是蓝图输入句「待检库存清单」的准确范围：
   * 可售品、残次品都不在清单里，因为它们不���要质检。
   */
  public PendingInspectionStockPage stock(
      UUID warehouseId, UUID locationId, UUID skuId, String keyword, String sort, int page, int size) {
    int max = Math.min(MAX_PAGE_SIZE, Math.max(1, size));
    int p = Math.max(0, page);
    var where = new ArrayList<String>();
    var binds = new ArrayList<Object>();
    where.add("q.role='PENDING_INSPECTION'");
    where.add("b.on_hand>0");
    if (warehouseId != null) {
      where.add("b.warehouse_id=?");
      binds.add(warehouseId);
    }
    if (locationId != null) {
      where.add("b.location_id=?");
      binds.add(locationId);
    }
    if (skuId != null) {
      where.add("b.sku_id=?");
      binds.add(skuId);
    }
    if (keyword != null && !keyword.isBlank()) {
      where.add("(s.code ilike ? or s.name ilike ? or w.code ilike ? or w.name ilike ? or loc.code ilike ?)");
      String k = "%" + keyword.trim() + "%";
      for (int i = 0; i < 5; i++) binds.add(k);
    }
    String sqlWhere = " where " + String.join(" and ", where);

    long total =
        sql.fetchOne("select count(*)" + STOCK_FROM + sqlWhere, binds.toArray()).get(0, Long.class);
    var rows =
        sql.fetch(
            "select b.id as bucket_id,b.balance_id,b.warehouse_id,w.code as warehouse_code,"
                + "w.name as warehouse_name,b.location_id,loc.code as location_code,"
                + "loc.name as location_name,loc.type as location_type,loc.pickable,"
                + "b.sku_id,s.code as sku_code,s.name as sku_name,b.lot_id,lot.lot_code,"
                + "b.quality_status_id,q.code as quality_status_code,q.name as quality_status_name,"
                + "b.on_hand,b.reserved,b.available,b.last_movement_at,"
                + "exists(select 1 from pending_inspection_task pt"
                + " where pt.tenant_id=b.tenant_id and pt.source_bucket_id=b.id"
                + " and pt.status not in ('DISPOSED','CANCELLED')) as has_open_task"
                + STOCK_FROM
                + sqlWhere
                + stockOrder(sort)
                + " limit ? offset ?",
            append(binds, max, (long) p * max).toArray());

    var sum =
        sql.fetchOne(
            "select coalesce(sum(b.on_hand),0) as on_hand,coalesce(sum(b.available),0) as available,"
                + "count(*) as bucket_count,count(distinct b.location_id) as location_count,"
                + "count(distinct b.sku_id) as sku_count"
                + STOCK_FROM
                + sqlWhere,
            binds.toArray());
    return new PendingInspectionStockPage(
        rows.stream().map(this::stockRow).toList(),
        total,
        p,
        max,
        decimal(sum, "on_hand"),
        decimal(sum, "available"),
        sum.get("bucket_count", Long.class),
        sum.get("location_count", Long.class),
        sum.get("sku_count", Long.class),
        "清单只列 role=PENDING_INSPECTION 且在手量大于 0 的库存行；"
            + "「已建单」列标出哪些已经被一张未完结的待检处理单占着（同一批货不能被处置两次）。");
  }

  private String stockOrder(String sort) {
    String key = sort == null ? "ON_HAND" : sort.toUpperCase(Locale.ROOT);
    if (!STOCK_SORTS.contains(key)) {
      throw new IllegalArgumentException("不支持的排序键：" + sort);
    }
    return switch (key) {
      case "SKU_CODE" -> " order by s.code,b.id";
      case "LOCATION_CODE" -> " order by loc.code,b.id";
      case "LAST_MOVEMENT" -> " order by b.last_movement_at desc nulls last,b.id";
      default -> " order by b.on_hand desc,b.id";
    };
  }

  private PendingInspectionStockRow stockRow(Record r) {
    return new PendingInspectionStockRow(
        r.get("bucket_id", UUID.class),
        r.get("balance_id", UUID.class),
        r.get("warehouse_id", UUID.class),
        r.get("warehouse_code", String.class),
        r.get("warehouse_name", String.class),
        r.get("location_id", UUID.class),
        r.get("location_code", String.class),
        r.get("location_name", String.class),
        r.get("location_type", String.class),
        Boolean.TRUE.equals(r.get("pickable", Boolean.class)),
        r.get("sku_id", UUID.class),
        r.get("sku_code", String.class),
        r.get("sku_name", String.class),
        r.get("lot_id", UUID.class),
        r.get("lot_code", String.class),
        r.get("quality_status_id", UUID.class),
        r.get("quality_status_code", String.class),
        r.get("quality_status_name", String.class),
        decimal(r, "on_hand"),
        decimal(r, "reserved"),
        decimal(r, "available"),
        r.get("last_movement_at", OffsetDateTime.class),
        Boolean.TRUE.equals(r.get("has_open_task", Boolean.class)));
  }

  // ================================================================ 下拉

  /** 建单与处置需要的下拉。质检模板来自 trade（只读），取不到时如实标「不可用」。 */
  public PendingInspectionOptions options(UUID warehouseId) {
    var warehouses =
        sql.fetch(
                "select id,code,name,country_code,timezone from warehouse"
                    + " where status='ACTIVE' order by code")
            .stream()
            .map(
                r ->
                    new PendingInspectionWarehouseRef(
                        r.get("id", UUID.class),
                        r.get("code", String.class),
                        r.get("name", String.class),
                        r.get("country_code", String.class),
                        r.get("timezone", String.class)))
            .toList();
    var qualities =
        sql.fetch(
                "select id,code,name,role,enabled from inventory_quality_status"
                    + " where enabled order by role,code")
            .stream()
            .map(
                r ->
                    new PendingInspectionQualityRef(
                        r.get("id", UUID.class),
                        r.get("code", String.class),
                        r.get("name", String.class),
                        r.get("role", String.class),
                        Boolean.TRUE.equals(r.get("enabled", Boolean.class))))
            .toList();
    var where = new ArrayList<String>();
    var binds = new ArrayList<Object>();
    where.add("loc.status='ACTIVE'");
    if (warehouseId != null) {
      where.add("loc.warehouse_id=?");
      binds.add(warehouseId);
    }
    var locations =
        sql.fetch(
                "select loc.id,loc.warehouse_id,w.code as warehouse_code,loc.code,loc.name,loc.type,"
                    + "loc.pickable,loc.status from warehouse_location loc"
                    + " join warehouse w on w.tenant_id=loc.tenant_id and w.id=loc.warehouse_id"
                    + " where "
                    + String.join(" and ", where)
                    + " order by w.code,loc.type,loc.code",
                binds.toArray())
            .stream()
            .map(
                r ->
                    new PendingInspectionLocationRef(
                        r.get("id", UUID.class),
                        r.get("warehouse_id", UUID.class),
                        r.get("warehouse_code", String.class),
                        r.get("code", String.class),
                        r.get("name", String.class),
                        r.get("type", String.class),
                        Boolean.TRUE.equals(r.get("pickable", Boolean.class)),
                        r.get("status", String.class)))
            .toList();

    boolean templatesAvailable = true;
    String templatesNote = null;
    List<PendingInspectionTemplateRef> templates = List.of();
    try {
      templates =
          trade.templates("Bearer internal")
              .stream()
              .map(
                  t ->
                      new PendingInspectionTemplateRef(
                          t.id(), t.code(), t.name(), t.inspectionType(), t.inspectionTypeLabel()))
              .toList();
    } catch (RuntimeException e) {
      // 取不到就如实说取不到：返回空模板列表会被读成「这个租户没有质检模板」，
      // 而真实情况可能是质检服务不可达。
      templatesAvailable = false;
      templatesNote = "质检模板来自 6.4.2.1（trade 服务），当前读不到：" + e.getMessage();
    }
    return new PendingInspectionOptions(
        warehouses, qualities, locations, templates, templatesAvailable, templatesNote, null);
  }

  // ================================================================ 任务列表

  /** 待检处理单列表（真分页 + 真筛选 + 排序白名单 + 汇总）。 */
  public PendingInspectionTaskPage page(
      String status,
      UUID warehouseId,
      UUID skuId,
      UUID receiptId,
      String keyword,
      String sort,
      int page,
      int size) {
    int max = Math.min(MAX_PAGE_SIZE, Math.max(1, size));
    int p = Math.max(0, page);
    var where = new ArrayList<String>();
    var binds = new ArrayList<Object>();
    if (status != null && !status.isBlank()) {
      where.add("t.status=?");
      binds.add(status.trim().toUpperCase(Locale.ROOT));
    }
    if (warehouseId != null) {
      where.add("t.warehouse_id=?");
      binds.add(warehouseId);
    }
    if (skuId != null) {
      where.add("t.sku_id=?");
      binds.add(skuId);
    }
    if (receiptId != null) {
      where.add("t.receipt_id=?");
      binds.add(receiptId);
    }
    if (keyword != null && !keyword.isBlank()) {
      where.add("(t.task_code ilike ? or t.receipt_code ilike ? or s.code ilike ? or s.name ilike ?)");
      String k = "%" + keyword.trim() + "%";
      for (int i = 0; i < 4; i++) binds.add(k);
    }
    String sqlWhere = where.isEmpty() ? "" : " where " + String.join(" and ", where);

    long total =
        sql.fetchOne("select count(*)" + TASK_FROM + sqlWhere, binds.toArray()).get(0, Long.class);
    var rows =
        sql.fetch(
            "select t.id,t.task_code,t.status,t.warehouse_id,w.code as warehouse_code,"
                + "t.sku_id,s.code as sku_code,s.name as sku_name,t.receipt_id,t.receipt_code,"
                + "t.receipt_line_id,t.quantity,t.inspection_order_code,t.result_type,"
                + "t.qualified_quantity,t.unqualified_quantity,t.concession_quantity,"
                + "t.disposition_summary,t.created_at,t.disposed_at,t.version"
                + TASK_FROM
                + sqlWhere
                + taskOrder(sort)
                + " limit ? offset ?",
            append(binds, max, (long) p * max).toArray());

    var counts =
        sql.fetchOne(
            "select coalesce(sum(t.quantity),0) as quantity,"
                + "coalesce(sum(coalesce(t.qualified_quantity,0)+coalesce(t.concession_quantity,0)),0)"
                + " as sellable,"
                + "coalesce(sum(coalesce(t.unqualified_quantity,0)),0) as nonsellable,"
                + "count(*) filter (where t.status='PENDING') as pending_count,"
                + "count(*) filter (where t.status='INSPECTION_REQUESTED') as requested_count,"
                + "count(*) filter (where t.status='RESULT_RECORDED') as recorded_count,"
                + "count(*) filter (where t.status='DISPOSED') as disposed_count,"
                + "count(*) filter (where t.status='CANCELLED') as cancelled_count"
                + TASK_FROM
                + sqlWhere,
            binds.toArray());
    return new PendingInspectionTaskPage(
        rows.stream().map(this::taskRow).toList(),
        total,
        p,
        max,
        decimal(counts, "quantity"),
        decimal(counts, "sellable"),
        decimal(counts, "nonsellable"),
        counts.get("pending_count", Long.class),
        counts.get("requested_count", Long.class),
        counts.get("recorded_count", Long.class),
        counts.get("disposed_count", Long.class),
        counts.get("cancelled_count", Long.class),
        "可售量 = 合格量 + 让步接收量；不可售量 = 不合格量。两者之和恒等于任务批量。");
  }

  private String taskOrder(String sort) {
    String key = sort == null ? "CREATED_AT" : sort.toUpperCase(Locale.ROOT);
    if (!TASK_SORTS.contains(key)) {
      throw new IllegalArgumentException("不支持的排序键：" + sort);
    }
    return switch (key) {
      case "DISPOSED_AT" -> " order by t.disposed_at desc nulls last,t.task_code";
      case "QUANTITY" -> " order by t.quantity desc,t.task_code";
      case "TASK_CODE" -> " order by t.task_code";
      default -> " order by t.created_at desc,t.task_code";
    };
  }

  private PendingInspectionTaskRow taskRow(Record r) {
    String status = r.get("status", String.class);
    BigDecimal qualified = r.get("qualified_quantity", BigDecimal.class);
    BigDecimal unqualified = r.get("unqualified_quantity", BigDecimal.class);
    BigDecimal concession = r.get("concession_quantity", BigDecimal.class);
    BigDecimal sellable =
        qualified == null && concession == null
            ? null
            : zeroIfNull(qualified).add(zeroIfNull(concession));
    BigDecimal nonSellable = unqualified == null ? null : unqualified;
    String summary = r.get("disposition_summary", String.class);
    return new PendingInspectionTaskRow(
        r.get("id", UUID.class),
        r.get("task_code", String.class),
        status,
        statusLabel(status),
        r.get("warehouse_id", UUID.class),
        r.get("warehouse_code", String.class),
        r.get("sku_id", UUID.class),
        r.get("sku_code", String.class),
        r.get("sku_name", String.class),
        r.get("receipt_id", UUID.class),
        r.get("receipt_code", String.class),
        r.get("receipt_line_id", UUID.class),
        decimal(r, "quantity"),
        r.get("inspection_order_code", String.class),
        r.get("result_type", String.class),
        resultLabel(r.get("result_type", String.class)),
        qualified,
        unqualified,
        concession,
        sellable,
        nonSellable,
        summary,
        summary == null || summary.isBlank() ? null : dispositionLabel(summary),
        r.get("created_at", OffsetDateTime.class),
        r.get("disposed_at", OffsetDateTime.class),
        r.get("version", Long.class));
  }

  // ================================================================ 详情

  /** 待检处理单详情：单头 + 来源库存 + 处置明细 + 事件台账 + 库存分录 + 可售量前后对照。 */
  public PendingInspectionTaskDetail detail(UUID taskId) {
    Record head = requireTask(taskId);
    UUID tenantId = head.get("tenant_id", UUID.class);
    var dispositions =
        sql.fetch(
                "select d.id,d.disposition_type,d.quantity,d.target_quality_status_id,"
                    + "q.code as target_quality_status_code,q.name as target_quality_status_name,"
                    + "d.target_location_id,loc.code as target_location_code,"
                    + "loc.name as target_location_name,loc.type as target_location_type,"
                    + "d.target_balance_id,d.target_bucket_id,d.note,d.created_at"
                    + " from pending_inspection_disposition d"
                    + " left join inventory_quality_status q"
                    + " on q.tenant_id=d.tenant_id and q.id=d.target_quality_status_id"
                    + " left join warehouse_location loc"
                    + " on loc.tenant_id=d.tenant_id and loc.id=d.target_location_id"
                    + " where d.tenant_id=? and d.task_id=? order by d.created_at,d.id",
                tenantId,
                taskId)
            .stream()
            .map(
                r ->
                    new PendingInspectionDispositionRow(
                        r.get("id", UUID.class),
                        r.get("disposition_type", String.class),
                        dispositionLabel(r.get("disposition_type", String.class)),
                        decimal(r, "quantity"),
                        r.get("target_quality_status_id", UUID.class),
                        r.get("target_quality_status_code", String.class),
                        r.get("target_quality_status_name", String.class),
                        r.get("target_location_id", UUID.class),
                        r.get("target_location_code", String.class),
                        r.get("target_location_name", String.class),
                        r.get("target_location_type", String.class),
                        r.get("target_balance_id", UUID.class),
                        r.get("target_bucket_id", UUID.class),
                        r.get("note", String.class),
                        r.get("created_at", OffsetDateTime.class)))
            .toList();

    var events =
        sql.fetch(
                "select id,event_type,occurred_at,actor_id,note from pending_inspection_event"
                    + " where tenant_id=? and task_id=? order by occurred_at,id",
                tenantId,
                taskId)
            .stream()
            .map(
                r ->
                    new PendingInspectionEventRow(
                        r.get("id", UUID.class),
                        r.get("event_type", String.class),
                        eventLabel(r.get("event_type", String.class)),
                        r.get("occurred_at", OffsetDateTime.class),
                        r.get("actor_id", UUID.class),
                        r.get("note", String.class)))
            .toList();

    var entries = entriesOf(tenantId, head.get("inventory_transaction_id", UUID.class));

    Record source =
        sql.fetchOne(
            "select b.id as bucket_id,b.on_hand,loc.code as location_code,loc.name as location_name,"
                + "loc.type as location_type,b.balance_id,q.code as quality_status_code,"
                + "q.name as quality_status_name,b.lot_id,lot.lot_code"
                + " from stock_bucket b"
                + " join warehouse_location loc"
                + " on loc.tenant_id=b.tenant_id and loc.id=b.location_id"
                + " join inventory_quality_status q"
                + " on q.tenant_id=b.tenant_id and q.id=b.quality_status_id"
                + " left join inventory_lot lot on lot.tenant_id=b.tenant_id and lot.id=b.lot_id"
                + " where b.tenant_id=? and b.id=?",
            tenantId,
            head.get("source_bucket_id", UUID.class));

    BigDecimal net =
        entries.stream()
            .map(PendingInspectionEntryRow::onHandDelta)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    return new PendingInspectionTaskDetail(
        taskRow(head),
        head.get("source_bucket_id", UUID.class),
        head.get("source_location_id", UUID.class),
        source == null ? null : source.get("location_code", String.class),
        source == null ? null : source.get("location_name", String.class),
        source == null ? null : source.get("location_type", String.class),
        head.get("source_balance_id", UUID.class),
        head.get("pending_quality_status_id", UUID.class),
        source == null ? null : source.get("quality_status_code", String.class),
        source == null ? null : source.get("quality_status_name", String.class),
        head.get("lot_id", UUID.class),
        source == null ? null : source.get("lot_code", String.class),
        source == null ? BigDecimal.ZERO : decimal(source, "on_hand"),
        new PendingInspectionOrderSnapshot(
            head.get("inspection_order_id", UUID.class),
            head.get("inspection_order_code", String.class),
            head.get("inspection_template_code", String.class),
            head.get("inspection_requested_at", OffsetDateTime.class),
            head.get("inspection_requested_by", UUID.class) == null
                ? null
                : head.get("inspection_requested_by", UUID.class).toString()),
        dispositions,
        events,
        entries,
        net,
        head.get("sellable_quantity_before", BigDecimal.class),
        head.get("sellable_quantity_after", BigDecimal.class),
        "分录净额为负数 = 这批货净离仓（报废 / 退货）；为 0 = 只是换了质量状态与库位，货还在本仓。");
  }

  private List<PendingInspectionEntryRow> entriesOf(UUID tenantId, UUID transactionId) {
    if (transactionId == null) {
      return List.of();
    }
    return sql.fetch(
            "select e.id,e.balance_id,b.quality_status_id,q.code as quality_status_code,"
                + "q.name as quality_status_name,q.role,e.on_hand_delta,e.reserved_delta,e.created_at"
                + " from inventory_entry e"
                + " join inventory_balance b on b.tenant_id=e.tenant_id and b.id=e.balance_id"
                + " join inventory_quality_status q"
                + " on q.tenant_id=b.tenant_id and q.id=b.quality_status_id"
                + " where e.tenant_id=? and e.transaction_id=? order by e.created_at,e.id",
            tenantId,
            transactionId)
        .stream()
        .map(
            r ->
                new PendingInspectionEntryRow(
                    r.get("id", UUID.class),
                    r.get("balance_id", UUID.class),
                    r.get("quality_status_id", UUID.class),
                    r.get("quality_status_code", String.class),
                    r.get("quality_status_name", String.class),
                    r.get("role", String.class),
                    decimal(r, "on_hand_delta"),
                    decimal(r, "reserved_delta"),
                    r.get("created_at", OffsetDateTime.class)))
        .toList();
  }

  // ================================================================ 建单

  /**
   * 建待检处理单（**不动库存**）。
   *
   * <p>建单只登记「这批待检货要走一次质检」，真正的库存动作在处置那一步。
   * 质量状态不是入参 —— 它从库存行读出来；让调用方自己声明「这批是待检的」
   * 等于允许它把可售品重新标成待检。
   */
  @Transactional
  public PendingInspectionTaskRow create(PendingInspectionTaskCreateInput input, UUID actorId) {
    if (input == null) {
      throw new IllegalArgumentException("建单参数不能为空");
    }
    UUID tenantId = currentTenant();
    Record bucket =
        sql.fetchOne(
            "select b.id,b.balance_id,b.sku_id,b.warehouse_id,b.location_id,b.lot_id,"
                + "b.quality_status_id,b.on_hand,q.role,s.code as sku_code,s.name as sku_name,"
                + "bal.legal_entity_id"
                + " from stock_bucket b"
                + " join inventory_quality_status q"
                + " on q.tenant_id=b.tenant_id and q.id=b.quality_status_id"
                + " join sku_ref s on s.tenant_id=b.tenant_id and s.id=b.sku_id"
                + " join inventory_balance bal"
                + " on bal.tenant_id=b.tenant_id and bal.id=b.balance_id"
                + " where b.tenant_id=? and b.id=?",
            tenantId,
            input.sourceBucketId());
    if (bucket == null) {
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND, "待检库存行不存在或无权访问（它可能已经被处置掉了）");
    }
    Record line =
        sql.fetchOne(
            "select l.id,l.receipt_id,l.sku_id,l.received_quantity,r.receipt_code,r.warehouse_id"
                + " from goods_receipt_line l"
                + " join goods_receipt r on r.tenant_id=l.tenant_id and r.id=l.receipt_id"
                + " where l.tenant_id=? and l.id=?",
            tenantId,
            input.receiptLineId());
    if (line == null) {
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND, "收货明细行不存在或无权访问：待检处理必须挂一张真实的收货单");
    }
    if (!"PENDING_INSPECTION".equals(bucket.get("role", String.class))) {
      throw new IllegalArgumentException(
          "选中的库存行质量角色是「"
              + bucket.get("role", String.class)
              + "」，不是待检：只有待检库存才需要质检");
    }
    if (!bucket.get("sku_id", UUID.class).equals(line.get("sku_id", UUID.class))) {
      throw new IllegalArgumentException("收货明细行与待检库存行的 SKU 不是同一个：质检单与库存对不上就是错的");
    }
    if (!bucket.get("warehouse_id", UUID.class).equals(line.get("warehouse_id", UUID.class))) {
      throw new IllegalArgumentException("收货单与待检库存行不在同一个仓库：不能跨仓质检");
    }
    BigDecimal quantity = quantity6(input.quantity(), "待检批量");
    BigDecimal onHand = decimal(bucket, "on_hand");
    if (quantity.compareTo(onHand) > 0) {
      throw new IllegalArgumentException(
          "待检批量 " + quantity + " 超过库位上的实际在手量 " + onHand + "：货已经不在待检区了");
    }
    BigDecimal received = line.get("received_quantity", BigDecimal.class);
    if (received == null || quantity.compareTo(received) > 0) {
      throw new IllegalArgumentException(
          "待检批量 " + quantity + " 超过收货明细行的实收量 " + received + "：待检量不能多于收进来的量");
    }

    String taskCode = nextTaskCode();
    UUID taskId = UUID.randomUUID();
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    sql.execute(
        "insert into pending_inspection_task(id,tenant_id,task_code,warehouse_id,legal_entity_id,"
            + "sku_id,sku_code,sku_name,receipt_id,receipt_code,receipt_line_id,source_bucket_id,"
            + "source_location_id,source_balance_id,lot_id,pending_quality_status_id,quantity,"
            + "status,notes,created_by,created_at,updated_at)"
            + " values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'PENDING',?,?,?::timestamptz,?::timestamptz)",
        taskId,
        tenantId,
        taskCode,
        bucket.get("warehouse_id", UUID.class),
        bucket.get("legal_entity_id", UUID.class),
        bucket.get("sku_id", UUID.class),
        bucket.get("sku_code", String.class),
        bucket.get("sku_name", String.class),
        line.get("receipt_id", UUID.class),
        line.get("receipt_code", String.class),
        line.get("id", UUID.class),
        bucket.get("id", UUID.class),
        bucket.get("location_id", UUID.class),
        bucket.get("balance_id", UUID.class),
        bucket.get("lot_id", UUID.class),
        bucket.get("quality_status_id", UUID.class),
        quantity,
        fit(input.notes(), 2000, "备注"),
        actorId,
        now,
        now);
    event(tenantId, taskId, "CREATED", now, actorId, "建单：待检 " + quantity + "（来源 " + line.get("receipt_code", String.class) + "）");
    return taskRow(requireTask(taskId));
  }

  // ================================================================ 发起质检

  /**
   * 发起质检任务（6.4.2.1 质检单，trade 服务）。
   *
   * <p>本服务只给 trade 三样它真正知道的事实：<b>哪个 SKU</b>、<b>来自哪张收货单的哪一行</b>、
   * <b>本次检多少</b>。抽样规则、检验项、结论怎么判，全在 trade 侧（它自己有模板与质检员）。
   *
   * <p>trade 不可达或拒绝时一律失败：<b>绝不</b>把「发起失败」记成「已发起」。
   */
  @Transactional
  public PendingInspectionTaskRow requestInspection(
      UUID taskId, PendingInspectionRequestInspectionInput input, UUID actorId, String bearer) {
    if (input == null) {
      throw new IllegalArgumentException("发起质检的参数不能为空");
    }
    Record head = lockTask(taskId);
    String status = head.get("status", String.class);
    if (!"PENDING".equals(status)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "待检处理单 " + head.get("task_code", String.class) + " 当前是「" + statusLabel(status) + "」，"
              + "只有待发起质检的单子可以发起质检");
    }
    UUID tenantId = head.get("tenant_id", UUID.class);
    UUID skuId = head.get("sku_id", UUID.class);
    String skuCode = head.get("sku_code", String.class);
    String skuName = head.get("sku_name", String.class);
    BigDecimal quantity = decimal(head, "quantity");
    String receiptCode = head.get("receipt_code", String.class);
    UUID receiptLineId = head.get("receipt_line_id", UUID.class);
    String lotCode =
        sql.fetchOne(
                "select lot_code from inventory_lot where tenant_id=? and id=?",
                tenantId,
                head.get("lot_id", UUID.class))
            == null
            ? null
            : sql.fetchOne(
                    "select lot_code from inventory_lot where tenant_id=? and id=?",
                    tenantId,
                    head.get("lot_id", UUID.class))
                .get("lot_code", String.class);

    var created =
        trade.create(
            new QualityInspectionTradePort.CreateRequest(
                input.templateId(),
                skuCode,
                skuId,
                skuName,
                receiptCode,
                receiptLineId,
                lotCode,
                quantity,
                fit(input.notes(), 2000, "备注")),
            bearer);

    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    sql.execute(
        "update pending_inspection_task set status='INSPECTION_REQUESTED',"
            + "inspection_order_id=?,inspection_order_code=?,inspection_template_id=?,"
            + "inspection_template_code=?,inspection_requested_at=?::timestamptz,"
            + "inspection_requested_by=?,version=version+1,updated_at=?::timestamptz where id=?",
        created.id(),
        created.inspectionNo() == null ? "" : created.inspectionNo(),
        input.templateId(),
        templateCode(input.templateId(), bearer),
        now,
        actorId,
        now,
        taskId);
    event(
        tenantId,
        taskId,
        "INSPECTION_REQUESTED",
        now,
        actorId,
        "发起质检任务：质检单 " + created.inspectionNo() + "（批量 " + quantity + "）");
    return taskRow(requireTask(taskId));
  }

  private String templateCode(UUID templateId, String bearer) {
    for (var t : trade.templates(bearer)) {
      if (t.id() != null && t.id().equals(templateId)) {
        return t.code() == null ? "" : t.code();
      }
    }
    return "";
  }

  // ================================================================ 录入质检结果

  /**
   * 录入质检结果。
   *
   * <p><b>数量不由调用方给</b>：质检结论的唯一来源是 trade 的 6.4.2.1 质检单，
   * 本服务在这里<b>回读</b>那张单的三量并冻结成本地快照。
   * 让操作员在这里再敲一遍数字，等于让同一个结论在两个服务各有一份 —— 迟早对不上，
   * 而对不上的时候谁也说不清该信谁。
   */
  @Transactional
  public PendingInspectionTaskRow recordResult(
      UUID taskId, PendingInspectionRecordResultInput input, UUID actorId, String bearer) {
    Record head = lockTask(taskId);
    String status = head.get("status", String.class);
    if (!"INSPECTION_REQUESTED".equals(status)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "待检处理单 " + head.get("task_code", String.class) + " 当前是「" + statusLabel(status) + "」，"
              + "只有已发起质检的单子可以录入质检结果");
    }
    UUID orderId = head.get("inspection_order_id", UUID.class);
    if (orderId == null) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "这张单还没有关联质检单，无法录入质检结果");
    }
    var order = trade.order(orderId, bearer);
    if (order == null) {
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND, "质检服务上找不到这张质检单（它可能已被作废）：" + orderId);
    }
    if (!"COMPLETED".equals(order.status())) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "质检单 " + order.inspectionNo() + " 还没完结（当前 " + order.status() + "），"
              + "没有结论就没有可处置的批次");
    }
    BigDecimal quantity = decimal(head, "quantity");
    BigDecimal qualified = zeroIfNull(order.qualifiedQuantity());
    BigDecimal unqualified = zeroIfNull(order.defectiveQuantity());
    BigDecimal concession = zeroIfNull(order.concessionQuantity());
    BigDecimal sum = qualified.add(unqualified).add(concession);
    if (sum.compareTo(quantity) != 0) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "质检单 " + order.inspectionNo() + " 的三量之和 " + sum + " 与本次待检批量 " + quantity
              + " 对不上：结论与库存不是同一批货，不能据此处置");
    }
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    sql.execute(
        "update pending_inspection_task set status='RESULT_RECORDED',result_type=?,"
            + "qualified_quantity=?,unqualified_quantity=?,concession_quantity=?,"
            + "result_recorded_at=?::timestamptz,result_recorded_by=?,result_note=?,"
            + "version=version+1,updated_at=?::timestamptz where id=?",
        order.result() == null ? "PASSED" : order.result(),
        qualified,
        unqualified,
        concession,
        now,
        actorId,
        fit(input == null ? null : input.notes(), 2000, "结论说明"),
        now,
        taskId);
    event(
        tenantId(head),
        taskId,
        "RESULT_RECORDED",
        now,
        actorId,
        "录入质检结果：合格 " + qualified + " / 不合格 " + unqualified + " / 让步 " + concession);
    return taskRow(requireTask(taskId));
  }

  // ================================================================ 处置

  /**
   * 处置决定 —— <b>本模块唯一动库存的动作</b>。
   *
   * <p>一次处置写一条 {@code inventory_transaction} + N 条分录：
   * 每条明细一条负向（待检 −Q），转可售 / 转残次的行再加一条正向（目标余额 +Q），
   * 报废 / 退货是离仓，只有负向。V252 的守卫在单头转 DISPOSED 的那一刻回读这些分录核对 P1/P2/P3/P4。
   */
  @Transactional
  public PendingInspectionTaskRow dispose(
      UUID taskId, PendingInspectionDisposeInput input, UUID actorId) {
    if (input == null || input.lines() == null || input.lines().isEmpty()) {
      throw new IllegalArgumentException("处置必须给出至少一个去向");
    }
    Record head = lockTask(taskId);
    String status = head.get("status", String.class);
    if (!"RESULT_RECORDED".equals(status)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "待检处理单 " + head.get("task_code", String.class) + " 当前是「" + statusLabel(status) + "」，"
              + "只有已录入质检结果的单子可以处置");
    }
    Long expected = input.expectedVersion();
    long actual = head.get("version", Long.class);
    if (expected == null) {
      throw new IllegalArgumentException("缺少 expectedVersion：写操作必须带上乐观锁版本号");
    }
    if (actual != expected) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "待检处理单已被他人修改（当前版本 " + actual + "，你的版本 " + expected + "），请刷新后重试");
    }
    UUID tenantId = head.get("tenant_id", UUID.class);
    BigDecimal quantity = decimal(head, "quantity");
    BigDecimal sellable =
        zeroIfNull(head.get("qualified_quantity", BigDecimal.class))
            .add(zeroIfNull(head.get("concession_quantity", BigDecimal.class)));

    // ---- 校验各行
    var parsed = new ArrayList<ParsedLine>();
    BigDecimal total = BigDecimal.ZERO;
    BigDecimal sellableSum = BigDecimal.ZERO;
    for (var line : input.lines()) {
      String type = parseDispositionType(line.dispositionType());
      BigDecimal qty = quantity6(line.quantity(), "处置数量");
      total = total.add(qty);
      if ("SELLABLE".equals(type)) {
        sellableSum = sellableSum.add(qty);
        if (line.targetQualityStatusId() == null || line.targetLocationId() == null) {
          throw new IllegalArgumentException("转可售必须指定目标质量状态与目标库位");
        }
      } else if ("DEFECTIVE".equals(type)) {
        if (line.targetQualityStatusId() == null || line.targetLocationId() == null) {
          throw new IllegalArgumentException("转残次必须指定目标质量状态与目标库位");
        }
      } else {
        if (line.targetQualityStatusId() != null || line.targetLocationId() != null) {
          throw new IllegalArgumentException(
              "报废 / 退货的货离开本仓，不得指定目标质量状态或目标库位");
        }
      }
      parsed.add(new ParsedLine(type, qty, line.targetQualityStatusId(), line.targetLocationId(),
          line.note()));
    }
    if (total.compareTo(quantity) != 0) {
      throw new IllegalArgumentException(
          "各去向数量之和 " + total + " 与本次待检批量 " + quantity + " 不相等："
              + "一批待检货必须被全部处置掉（处置一半意味着另一半永远没有结论）");
    }
    if (sellableSum.compareTo(sellable) > 0) {
      throw new IllegalArgumentException(
          "转可售的量 " + sellableSum + " 超过质检结论里的可售量 " + sellable
              + "（合格量 + 让步接收量）：不合格品不能被转成可售");
    }

    // ---- 目标质量状态与库位的服务端校验（数据库层还会再挡一道）
    UUID warehouseId = head.get("warehouse_id", UUID.class);
    UUID legalEntityId = head.get("legal_entity_id", UUID.class);
    UUID skuId = head.get("sku_id", UUID.class);
    UUID lotId = head.get("lot_id", UUID.class);
    UUID sourceBucketId = head.get("source_bucket_id", UUID.class);
    UUID sourceBalanceId = head.get("source_balance_id", UUID.class);
    UUID sourceLocationId = head.get("source_location_id", UUID.class);
    BigDecimal before = sellableQuantity(tenantId, skuId, warehouseId);

    boolean allReturn = parsed.stream().allMatch(l -> "RETURN".equals(l.type()));
    String kind = allReturn ? "RETURN" : "ADJUSTMENT";
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    UUID transactionId = UUID.randomUUID();
    sql.execute(
        "insert into inventory_transaction(id,tenant_id,kind,ref_type,ref_id,occurred_at,actor_id,notes)"
            + " values(?,?,?,'PENDING_INSPECTION',?,?::timestamptz,?,?)",
        transactionId,
        tenantId,
        kind,
        taskId,
        now,
        actorId,
        "待检处置：转可售 " + sellableSum + "，其余 " + (total.subtract(sellableSum))
            + "（残次 / 报废 / 退货），批量 " + quantity);

    for (var line : parsed) {
      moveOut(tenantId, sourceBucketId, sourceBalanceId, sourceLocationId, lotId, line.quantity(), now);
      sql.execute(
          "insert into inventory_entry(id,tenant_id,transaction_id,balance_id,on_hand_delta,reserved_delta)"
              + " values(?,?,?,?,?,0)",
          UUID.randomUUID(),
          tenantId,
          transactionId,
          sourceBalanceId,
          line.quantity().negate());

      UUID targetBalanceId = null;
      UUID targetBucketId = null;
      if ("SELLABLE".equals(line.type()) || "DEFECTIVE".equals(line.type())) {
        String role = requireRole(tenantId, line.targetQualityStatusId());
        String expectedRole = "SELLABLE".equals(line.type()) ? "SELLABLE" : "DEFECTIVE";
        if (!expectedRole.equals(role)) {
          throw new IllegalArgumentException(
              "目标质量状态角色是「" + role + "」，与去向 " + line.type() + " 不一致");
        }
        requireLocationMatches(tenantId, line.targetLocationId(), role, warehouseId);
        targetBalanceId =
            balanceId(tenantId, skuId, warehouseId, legalEntityId, line.targetQualityStatusId());
        targetBucketId =
            moveIn(
                tenantId,
                targetBalanceId,
                line.targetLocationId(),
                lotId,
                line.quantity(),
                now);
        sql.execute(
            "update inventory_balance set on_hand=on_hand+?,version=version+1,updated_at=now()"
                + " where id=?",
            line.quantity(),
            targetBalanceId);
        sql.execute(
            "insert into inventory_entry(id,tenant_id,transaction_id,balance_id,on_hand_delta,"
                + "reserved_delta) values(?,?,?,?,?,0)",
            UUID.randomUUID(),
            tenantId,
            transactionId,
            targetBalanceId,
            line.quantity());
      }
      sql.execute(
          "insert into pending_inspection_disposition(id,tenant_id,task_id,disposition_type,quantity,"
              + "target_quality_status_id,target_location_id,target_balance_id,target_bucket_id,note,"
              + "created_at) values(?,?,?,?,?,?,?,?,?,?,?::timestamptz)",
          UUID.randomUUID(),
          tenantId,
          taskId,
          line.type(),
          line.quantity(),
          line.targetQualityStatusId(),
          line.targetLocationId(),
          targetBalanceId,
          targetBucketId,
          fit(line.note(), 2000, "处置说明"),
          now);
    }

    BigDecimal after = sellableQuantity(tenantId, skuId, warehouseId);
    String summary = parsed.stream().map(ParsedLine::type).distinct().count() > 1 ? "MIXED" : parsed.get(0).type();
    sql.execute(
        "update pending_inspection_task set status='DISPOSED',inventory_transaction_id=?,"
            + "disposition_summary=?,sellable_quantity_before=?,sellable_quantity_after=?,"
            + "disposed_at=?::timestamptz,disposed_by=?,version=version+1,updated_at=?::timestamptz"
            + " where id=?",
        transactionId,
        summary,
        before,
        after,
        now,
        actorId,
        now,
        taskId);
    event(
        tenantId,
        taskId,
        "DISPOSED",
        now,
        actorId,
        "处置完成：转可售 " + sellableSum + "，其余 " + (total.subtract(sellableSum))
            + "；可售量 " + before + " → " + after);
    return taskRow(requireTask(taskId));
  }

  // ================================================================ 取消

  /**
   * 取消待检处理。
   *
   * <p>已发起质检的取消会<b>同时作废 trade 侧的质检单</b> —— 否则 trade 里会留下一张
   * 永远不会被判的质检单，而仓库这边显示「已取消」，两边对不上账。
   * trade 不可达时取消失败（单子保持原状）：宁可留着一条待处理单，也不留下两边不一致的假象。
   */
  @Transactional
  public PendingInspectionTaskRow cancel(
      UUID taskId, PendingInspectionCancelInput input, UUID actorId, String bearer) {
    Record head = lockTask(taskId);
    String status = head.get("status", String.class);
    if (!"PENDING".equals(status) && !"INSPECTION_REQUESTED".equals(status)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "待检处理单 " + head.get("task_code", String.class) + " 当前是「" + statusLabel(status) + "」，"
              + "只有还没录入质检结果的单子可以取消");
    }
    String reason = fit(input == null ? null : input.reason(), 200, "取消原因");
    if (reason.isBlank()) {
      throw new IllegalArgumentException("取消必须给出理由：没有理由的取消等于把记录抹掉");
    }
    UUID orderId = head.get("inspection_order_id", UUID.class);
    if (orderId != null) {
      trade.cancel(orderId, reason, bearer);
    }
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    sql.execute(
        "update pending_inspection_task set status='CANCELLED',cancelled_at=?::timestamptz,"
            + "cancelled_by=?,cancel_reason=?,version=version+1,updated_at=?::timestamptz where id=?",
        now,
        actorId,
        reason,
        taskId);
    event(
        tenantId(head),
        taskId,
        "CANCELLED",
        now,
        actorId,
        "取消：" + reason + (orderId == null ? "" : "（同时作废质检单）"));
    return taskRow(requireTask(taskId));
  }

  // ================================================================ 库存写入

  /** 从待检 bucket 扣量（并同步减余额行）。 */
  private void moveOut(
      UUID tenantId,
      UUID bucketId,
      UUID balanceId,
      UUID locationId,
      UUID lotId,
      BigDecimal qty,
      OffsetDateTime at) {
    Record bucket =
        sql.fetchOne(
            "select id,on_hand,reserved from stock_bucket where tenant_id=? and id=?",
            tenantId,
            bucketId);
    if (bucket == null) {
      throw new IllegalStateException("待检库存行已不存在，无法处置");
    }
    BigDecimal onHand = decimal(bucket, "on_hand");
    if (onHand.compareTo(qty) < 0) {
      throw new IllegalArgumentException(
          "待检库存行上只有 " + onHand + "，要处置 " + qty + "：货已经不在待检区了，先刷新清单");
    }
    sql.execute(
        "update stock_bucket set on_hand=on_hand-?,"
            + "last_movement_at=greatest(?::timestamptz,last_movement_at + interval '1 microsecond')"
            + " where id=?",
        qty,
        at,
        bucketId);
    sql.execute(
        "update inventory_balance set on_hand=on_hand-?,version=version+1,updated_at=now() where id=?",
        qty,
        balanceId);
  }

  /** 转入目标库位：找得到就加，找不到就新建一条 bucket（事实行永不可删，只增不减）。 */
  private UUID moveIn(
      UUID tenantId,
      UUID balanceId,
      UUID locationId,
      UUID lotId,
      BigDecimal qty,
      OffsetDateTime at) {
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
              + "last_movement_at=greatest(?::timestamptz,last_movement_at + interval '1 microsecond')"
              + " where id=?",
          qty,
          at,
          existing.get("id", UUID.class));
      return existing.get("id", UUID.class);
    }
    Record bal = sql.fetchOne("select * from inventory_balance where id=?", balanceId);
    UUID bucketId = UUID.randomUUID();
    sql.execute(
        "insert into stock_bucket(id,tenant_id,legal_entity_id,sku_id,warehouse_id,location_id,"
            + "lot_id,quality_status_id,balance_id,on_hand,reserved,last_movement_at)"
            + " values(?,?,?,?,?,?,?,?,?,?,0,?::timestamptz)",
        bucketId,
        tenantId,
        bal.get("legal_entity_id", UUID.class),
        bal.get("sku_id", UUID.class),
        bal.get("warehouse_id", UUID.class),
        locationId,
        lotId,
        bal.get("quality_status_id", UUID.class),
        balanceId,
        qty,
        at);
    return bucketId;
  }

  /** 取（或建）一条余额行。 */
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
    UUID id = UUID.randomUUID();
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

  /**
   * 该 SKU 在本仓的<b>可售量</b>（7.1.1.3 口径：role='SELLABLE' ∧ 库位可拣 ∧ 库位启用）。
   *
   * <p>口径与 {@code AvailableQuantityService} 的「片一」逐字一致：可售基数只认可售质量状态
   * + 可拣启用的库位。待检品与残次品本来就不在这里面 —— 这就是蓝图那句
   * 「待检品不可售」在可用量口径上的落点。
   */
  private BigDecimal sellableQuantity(UUID tenantId, UUID skuId, UUID warehouseId) {
    Record r =
        sql.fetchOne(
            "select coalesce(sum(b.available),0) as sellable from stock_bucket b"
                + " join inventory_quality_status q"
                + " on q.tenant_id=b.tenant_id and q.id=b.quality_status_id"
                + " join warehouse_location loc"
                + " on loc.tenant_id=b.tenant_id and loc.warehouse_id=b.warehouse_id"
                + " and loc.id=b.location_id"
                + " where b.tenant_id=? and b.sku_id=? and b.warehouse_id=?"
                + " and q.role='SELLABLE' and loc.pickable and loc.status='ACTIVE'",
            tenantId,
            skuId,
            warehouseId);
    return decimal(r, "sellable");
  }

  // ================================================================ 校验与工具

  private String requireRole(UUID tenantId, UUID qualityStatusId) {
    Record r =
        sql.fetchOne(
            "select role from inventory_quality_status where tenant_id=? and id=?",
            tenantId,
            qualityStatusId);
    if (r == null) {
      throw new IllegalArgumentException("目标质量状态不存在或无权访问：" + qualityStatusId);
    }
    return r.get("role", String.class);
  }

  /** 目标库位类型必须与质量角色匹配（复用 8.1.2.2 的权威函数）。 */
  private void requireLocationMatches(UUID tenantId, UUID locationId, String role, UUID warehouseId) {
    Record loc =
        sql.fetchOne(
            "select loc.type,loc.warehouse_id from warehouse_location loc"
                + " where loc.tenant_id=? and loc.id=?",
            tenantId,
            locationId);
    if (loc == null) {
      throw new IllegalArgumentException("目标库位不存在或无权访问：" + locationId);
    }
    if (!warehouseId.equals(loc.get("warehouse_id", UUID.class))) {
      throw new IllegalArgumentException("目标库位属于另一个仓库：不能跨仓处置");
    }
    String type = loc.get("type", String.class);
    String required = requiredLocationType(role);
    if (required == null) {
      throw new IllegalArgumentException("质量状态角色「" + role + "」没有对应的库位类型");
    }
    if (!required.equals(type)) {
      throw new IllegalArgumentException(
          "质量状态「" + role + "」只能进 " + required + " 类库位，目标库位是 " + type + " 类");
    }
  }

  /** 质量角色 → 库位类型。SQL 里的权威函数是 location_allocation_required_type（V106）。 */
  private String requiredLocationType(String role) {
    Record r =
        sql.fetchOne("select location_allocation_required_type(?) as t", role);
    return r == null ? null : r.get("t", String.class);
  }

  private Record requireTask(UUID taskId) {
    Record head =
        sql.fetchOne("select * from pending_inspection_task where id=?", taskId);
    if (head == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "待检处理单不存在或无权查看");
    }
    return head;
  }

  private Record lockTask(UUID taskId) {
    Record head =
        sql.fetchOne("select * from pending_inspection_task where id=? for update", taskId);
    if (head == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "待检处理单不存在或无权查看");
    }
    return head;
  }

  private void event(
      UUID tenantId, UUID taskId, String type, OffsetDateTime at, UUID actorId, String note) {
    sql.execute(
        "insert into pending_inspection_event(id,tenant_id,task_id,event_type,occurred_at,actor_id,note)"
            + " values(?,?,?,?,?::timestamptz,?,?)",
        UUID.randomUUID(),
        tenantId,
        taskId,
        type,
        at,
        actorId,
        note);
  }

  private String nextTaskCode() {
    Long value = sql.fetchOne("select nextval('pending_inspection_task_code_seq')").get(0, Long.class);
    long n = value == null ? 1L : value;
    return "PI-" + String.format("%08d", n);
  }

  private static String parseDispositionType(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new IllegalArgumentException("处置去向不能为空：只能是 SELLABLE / DEFECTIVE / SCRAP / RETURN");
    }
    String type = raw.trim().toUpperCase(Locale.ROOT);
    if (!List.of("SELLABLE", "DEFECTIVE", "SCRAP", "RETURN").contains(type)) {
      throw new IllegalArgumentException("不支持的处置去向：" + raw);
    }
    return type;
  }

  static String statusLabel(String status) {
    if (status == null) return "";
    return switch (status) {
      case "PENDING" -> "待发起质检";
      case "INSPECTION_REQUESTED" -> "已发起质检";
      case "RESULT_RECORDED" -> "已录入结果";
      case "DISPOSED" -> "已处置";
      case "CANCELLED" -> "已取消";
      default -> status;
    };
  }

  static String dispositionLabel(String type) {
    if (type == null) return "";
    return switch (type) {
      case "SELLABLE" -> "转可售";
      case "DEFECTIVE" -> "转残次";
      case "SCRAP" -> "报废";
      case "RETURN" -> "退货";
      case "MIXED" -> "混合去向";
      default -> type;
    };
  }

  static String resultLabel(String result) {
    if (result == null) return null;
    return switch (result) {
      case "PASSED" -> "合格";
      case "PARTIAL" -> "部分合格";
      case "FAILED" -> "整批不合格";
      case "CONCESSION_ACCEPTED" -> "让步接收";
      default -> result;
    };
  }

  static String eventLabel(String type) {
    if (type == null) return "";
    return switch (type) {
      case "CREATED" -> "建单";
      case "INSPECTION_REQUESTED" -> "发起质检";
      case "RESULT_RECORDED" -> "录入结果";
      case "DISPOSED" -> "处置";
      case "CANCELLED" -> "取消";
      default -> type;
    };
  }

  /** 数量刻度：与 DB 的 numeric(19,6) 对齐，同时拒绝超过 4 位小数的量（库存刻度是 4 位）。 */
  private static BigDecimal quantity6(BigDecimal value, String label) {
    if (value == null) {
      throw new IllegalArgumentException(label + "不能为空");
    }
    if (value.signum() <= 0) {
      throw new IllegalArgumentException(label + "必须大于 0");
    }
    if (value.scale() > 4 && value.stripTrailingZeros().scale() > 4) {
      throw new IllegalArgumentException(
          label + " " + value + " 的小数位超过 4 位：库存刻度是 4 位，多出来的位会被静默抹掉");
    }
    return value.setScale(QUANTITY_SCALE, RoundingMode.UNNECESSARY);
  }

  private static BigDecimal decimal(Record r, String column) {
    if (r == null) {
      return BigDecimal.ZERO.setScale(QUANTITY_SCALE, RoundingMode.HALF_UP);
    }
    BigDecimal v = r.get(column, BigDecimal.class);
    return v == null ? BigDecimal.ZERO.setScale(QUANTITY_SCALE, RoundingMode.HALF_UP) : v;
  }

  private static BigDecimal zeroIfNull(BigDecimal value) {
    return value == null ? BigDecimal.ZERO.setScale(QUANTITY_SCALE, RoundingMode.HALF_UP) : value;
  }

  /** 文本长度校验：超长直接拒绝（DB 截断会让「凭证说明」悄悄少几个字）。 */
  private static String fit(String value, int max, String label) {
    String v = value == null ? "" : value.trim();
    if (v.length() > max) {
      throw new IllegalArgumentException(label + "超过 " + max + " 个字符");
    }
    return v;
  }

  private static List<Object> append(List<Object> binds, Object... more) {
    var copy = new ArrayList<>(binds);
    for (Object o : more) {
      copy.add(o);
    }
    return copy;
  }

  private UUID currentTenant() {
    return sql.fetchOne("select nullif(current_setting('app.current_tenant',true),'')::uuid")
        .get(0, UUID.class);
  }

  private UUID tenantId(Record head) {
    return head.get("tenant_id", UUID.class);
  }

  /** 处置一行（内部结构）。 */
  private record ParsedLine(
      String type, BigDecimal quantity, UUID targetQualityStatusId, UUID targetLocationId, String note) {}
}
