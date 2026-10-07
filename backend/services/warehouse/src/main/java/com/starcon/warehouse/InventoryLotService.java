package com.starcon.warehouse;

import static com.starcon.warehouse.InventoryLotModels.*;

import java.math.BigDecimal;
import java.time.LocalDate;
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
 * 7.3.1.1 批次管理。
 *
 * <p>蓝图：「管理批次（lot）：批号、生产日期、失效日期、来源采购单/供应商，
 * 支持按批次查询库存与流向追溯」，输出「批次档案 + 批次库存分布 + 出入库历史」。
 *
 * <p>四条实现纪律：
 *
 * <ul>
 *   <li><b>批次只有一张表</b>。V2 建 {@code inventory_lot} 并用外键把批次接进
 *       {@code stock_bucket}，本模块只往它上面补属性（V57），不另建「批次档案表」——
 *       两个真相来源迟早会对不上，而且极难发现。
 *   <li><b>库存分布真从 {@code stock_bucket} 聚合</b>：按批次 {@code LEFT JOIN LATERAL}，
 *       走 {@code (tenant_id, lot_id)} 索引。仓库级合计与库位级合计都必须等于总计，
 *       这个等式由集成测试逐条断言，不靠「看起来对」。
 *   <li><b>出入库历史复用 7.1.2.1 台账</b>，不另写一套流水查询；归属口径与局限随响应返回，
 *       详见 {@link #movements}。
 *   <li><b>数量一律 numeric(19,4) + BigDecimal</b>；生产/失效/收货日期是 {@code date} 业务日期，
 *       绑定用 {@code ?::date}。租户来自已认证成员关系（控制器传入），不接受任何客户端租户参数。
 * </ul>
 */
@Service
public class InventoryLotService {

  /** 单页最大行数。批次是主数据，量级远小于流水，但仍要克制。 */
  static final int MAX_PAGE_SIZE = 200;

  /** 排序键白名单：来自 query string，只允许这些。 */
  private static final List<String> SORTS =
      List.of(
          "LOT_CODE",
          "EXPIRY",
          "ON_HAND",
          "RECEIVED_ON",
          "PRODUCED_ON",
          "CREATED_AT",
          "UPDATED_AT");

  /** 批次状态词表。与 {@code inventory_lot.status} 的 CHECK 逐值对应。 */
  static final List<String> STATUSES = List.of("ACTIVE", "DEPLETED", "EXPIRED");

  private static final List<String> ORIGINS =
      Arrays.stream(LotOrigin.values()).map(Enum::name).toList();

  private static final List<String> SOURCE_SYSTEMS =
      Arrays.stream(LotSourceSystem.values()).map(Enum::name).toList();

  /**
   * 档案基表 FROM（不含数量列）。
   *
   * <p>{@code a} 是别名后缀，让同一段 FROM / WHERE 能在「列表主查询」与「汇总子查询」里复用
   * —— 与 {@code InventoryLedgerService} 的 {@code prefix} 是同一条理由。
   */
  private static String lotFrom(String a) {
    return " from inventory_lot "
        + a
        + "l"
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
        + ".sku_id";
  }

  /**
   * 数量聚合的 {@code LEFT JOIN LATERAL}。
   *
   * <p>用 LATERAL 而不是「全表 GROUP BY 的 CTE」：CTE 会把整个 {@code stock_bucket} 扫一遍
   * 再按批次归并，LATERAL 只聚合当前这一行的批次，走 {@code (tenant_id, lot_id)} 索引。
   *
   * <p>批次一个 bucket 都没有时（刚建、还没收货）聚合返回一行全 NULL，外层 coalesce 成 0 ——
   * 这正是「批次已建、库存为零」该有的样子，不能让它从列表里消失。
   */
  private static String lotAggJoin(String a) {
    return " left join lateral (select sum(b.on_hand) as on_hand,sum(b.reserved) as reserved,"
        + " sum(b.available) as available,count(*) as bucket_count,"
        + " count(distinct b.location_id) as location_count,"
        + " count(distinct b.warehouse_id) as warehouse_count"
        + " from stock_bucket b where b.tenant_id=l"
        + a
        + ".tenant_id and b.lot_id=l"
        + a
        + ".id) agg"
        + a
        + " on true";
  }

  /**
   * 批次行投影。列表与单条档案详情**必须用同一份列清单** ——
   * 两处各写一遍的话，详情页少一列不会报错，只会让「列表有、点进去没有」这种问题潜伏很久。
   *
   * <p>{@code expired} 是日历事实（失效日期早于数据库当天），与 {@code status=EXPIRED} 是两回事，
   * 两者都摆出来，界面上才看得出「运营还没标过期，但日期已经过了」。
   */
  private static final String ROW_SELECT =
      """
      select l.id as lot_id,l.sku_id,s.code as sku_code,s.name as sku_name,l.lot_code,\
       l.produced_on,l.expires_on,l.received_on,l.origin,\
       l.source_system,l.source_ref_id,l.source_ref_code,\
       l.supplier_ref_id,l.supplier_name,l.status,l.notes,l.updated_at,\
       coalesce(agg.on_hand,0) as on_hand,coalesce(agg.reserved,0) as reserved,\
       coalesce(agg.available,0) as available,\
       coalesce(agg.bucket_count,0) as bucket_count,\
       coalesce(agg.location_count,0) as location_count,\
       coalesce(agg.warehouse_count,0) as warehouse_count,\
       (l.expires_on is not null and l.expires_on<current_date) as expired,\
       case when l.expires_on is null then null else (l.expires_on-current_date) end \
         as days_to_expiry\
      """;

  private final DSLContext sql;
  private final InventoryLedgerService ledger;

  public InventoryLotService(DSLContext sql, InventoryLedgerService ledger) {
    this.sql = sql;
    this.ledger = ledger;
  }

  // ================================================================ 批次档案列表

  /** 批次档案列表 + 全量汇总。 */
  public LotArchivePage page(LotArchiveQuery query) {
    int size = Math.min(MAX_PAGE_SIZE, Math.max(1, query.size() == null ? 50 : query.size()));
    int pageNumber = Math.max(0, query.page() == null ? 0 : query.page());

    var where = new ArrayList<String>();
    var binds = new ArrayList<Object>();
    conditions(query, "", where, binds);
    String sqlWhere = where.isEmpty() ? "" : " where " + String.join(" and ", where);

    // 总批数只查基表。inventory_lot 与 sku_ref 是 1:1（两边都有 UNIQUE(tenant_id,id)），
    // 所以 count(*) 精确等于批次数；而所有数量类条件都写成对 stock_bucket 的 exists，
    // 不引用 agg —— 计数查询因此根本不需要 join 数量聚合。
    long total =
        sql.fetchOne("select count(*)" + lotFrom("") + sqlWhere, binds.toArray()).get(0, Long.class);

    var rows =
        sql.fetch(
            ROW_SELECT
                + lotFrom("")
                + lotAggJoin("")
                + sqlWhere
                + order(query)
                + " limit ? offset ?",
            append(binds, size, (long) pageNumber * size).toArray());

    return new LotArchivePage(
        summary(query),
        rows.stream().map(InventoryLotService::row).toList(),
        statusOptions(),
        originOptions(),
        sourceSystemOptions(),
        total,
        pageNumber,
        size);
  }

  /**
   * 批次汇总。**全量**，不受分页影响。
   *
   * <p>三处口径写死在 javadoc，界面按它显示：
   *
   * <ul>
   *   <li>{@code expiredLotCount}：失效日期早于数据库当天。日历事实。
   *   <li>{@code depletedLotCount}：在手聚合为 0（一个 bucket 都没有也算）。数量事实。
   *   <li>{@code expiringLotCount}：带失效日期且**尚未过期**的批次数。给了
   *       {@code expiringWithinDays} 时 WHERE 已经把范围收窄到该窗口，所以这里**不再重复加条件**
   *       （重复加就要多一个绑定，绑定顺序一错整条语句就错位）。不传窗口时它等于
   *       「还有效期的批次有多少」，不是 0。
   * </ul>
   */
  private LotArchiveSummary summary(LotArchiveQuery query) {
    var where = new ArrayList<String>();
    var binds = new ArrayList<Object>();
    conditions(query, "", where, binds);
    String sqlWhere = where.isEmpty() ? "" : " where " + String.join(" and ", where);

    Record main =
        sql.fetchOne(
            "select count(*) as lot_count,count(distinct l.sku_id) as sku_count,"
                + " coalesce(sum(agg.on_hand),0) as on_hand,coalesce(sum(agg.reserved),0) as reserved,"
                + " coalesce(sum(agg.available),0) as available,"
                + " count(*) filter (where l.expires_on is not null and l.expires_on<current_date)"
                + "   as expired_lot_count,"
                + " count(*) filter (where coalesce(agg.on_hand,0)=0) as depleted_lot_count,"
                + " count(*) filter (where l.expires_on is not null and l.expires_on>=current_date)"
                + "   as expiring_lot_count,"
                + " count(*) filter (where l.origin='MANUAL') as manual_lot_count,"
                + " count(*) filter (where l.source_ref_id is not null) as source_linked_lot_count,"
                + " min(l.expires_on) filter (where l.expires_on>=current_date) as earliest_expiry"
                + lotFrom("")
                + lotAggJoin("")
                + sqlWhere,
            binds.toArray());

    // 跨批次的去重计数：一个批次横跨 3 个仓，把 3 行的 warehouse_count 加起来当「仓库数」是错的。
    // 所以回到 stock_bucket 按命中的批次集合去重数一次。
    Record scope =
        sql.fetchOne(
            "select count(distinct b.warehouse_id) as warehouse_count,"
                + " count(distinct b.location_id) as location_count"
                + " from stock_bucket b where b.lot_id in (select l.id"
                + lotFrom("")
                + sqlWhere
                + ")",
            binds.toArray());

    return new LotArchiveSummary(
        main.get("lot_count", Long.class),
        main.get("sku_count", Long.class),
        scope.get("warehouse_count", Long.class),
        decimal(main, "on_hand"),
        decimal(main, "reserved"),
        decimal(main, "available"),
        main.get("expired_lot_count", Long.class),
        main.get("depleted_lot_count", Long.class),
        main.get("expiring_lot_count", Long.class),
        main.get("source_linked_lot_count", Long.class),
        main.get("manual_lot_count", Long.class),
        main.get("earliest_expiry", LocalDate.class));
  }

  // ================================================================ 下拉词表

  /**
   * 下拉词表：**租户级，不随筛选与时间变化**。
   *
   * <p>与 {@code InventoryLedgerService.refTypes()} 同一纪律：它是「这个租户开过哪些批次状态 /
   * 建立方式 / 来源系统」的清单，跟着筛选抖动的下拉会让人以为某类批次不存在。
   */
  private List<LotOption> statusOptions() {
    return options(
        "select status as value,case status when 'ACTIVE' then '在库'"
            + " when 'DEPLETED' then '已用尽' when 'EXPIRED' then '已过期' end as label,"
            + " count(*) as lot_count from inventory_lot group by status order by status");
  }

  private List<LotOption> originOptions() {
    return options(
        "select origin as value,case origin when 'RECEIPT' then '收货生成'"
            + " else '手工建立' end as label,count(*) as lot_count"
            + " from inventory_lot group by origin order by origin");
  }

  private List<LotOption> sourceSystemOptions() {
    return options(
        "select source_system as value,case source_system when 'PURCHASE' then '采购单'"
            + " when 'RECEIPT' then '收货单' else '手工补录' end as label,count(*) as lot_count"
            + " from inventory_lot where source_system is not null"
            + " group by source_system order by source_system");
  }

  private List<LotOption> options(String statement) {
    return sql.fetch(statement)
        .map(
            r ->
                new LotOption(
                    r.get("value", String.class),
                    r.get("label", String.class),
                    r.get("lot_count", Long.class)));
  }

  // ================================================================ 批次库存分布

  /**
   * 单个批次的档案详情（列表行的同构单条版）。
   *
   * <p>与 {@link #distribution} 分开：档案详情是「这批货是什么」（批号、生产/失效/收货日期、
   * 来源单据、供应商、备注），库存分布是「这批货现在在哪」（仓库/库位/质量状态切片）。
   * 蓝图把两者列为两条输出，界面上也是两个区域。
   *
   * <p>跨租户访问在 RLS 下查不到 → 404，不泄露存在性。
   */
  public LotArchiveRow archive(UUID lotId) {
    Record r =
        sql.fetchOne(ROW_SELECT + lotFrom("") + lotAggJoin("") + " where l.id=?", lotId);
    if (r == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "批次不存在或无权查看");
    }
    return row(r);
  }

  /**
   * 批次库存分布：仓库级 + 库位级，**真从 {@code stock_bucket} 聚合**。
   *
   * <p>自检等式（集成测试逐条断言）：
   * {@code totalOnHand == Σ warehouses.onHand == Σ locations.onHand}，
   * 且 {@code totalAvailable == totalOnHand − totalReserved}。
   *
   * <p>跨租户访问在 RLS 下表现为查不到，返回 404 而不是 403 —— 403 会泄露「这个批次存在」。
   */
  public LotDistribution distribution(UUID lotId) {
    Record header =
        sql.fetchOne(
            "select l.id as lot_id,l.sku_id,s.code as sku_code,s.name as sku_name,l.lot_code,"
                + " l.status,l.expires_on,"
                + " (l.expires_on is not null and l.expires_on<current_date) as expired,"
                + " coalesce(agg.on_hand,0) as on_hand,coalesce(agg.reserved,0) as reserved,"
                + " coalesce(agg.available,0) as available,"
                + " coalesce(agg.bucket_count,0) as bucket_count,"
                + " coalesce(agg.location_count,0) as location_count,"
                + " coalesce(agg.warehouse_count,0) as warehouse_count"
                + lotFrom("")
                + lotAggJoin("")
                + " where l.id=?",
            lotId);
    if (header == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "批次不存在或无权查看");
    }

    var warehouses =
        sql.fetch(
                "select b.warehouse_id,w.code as warehouse_code,w.name as warehouse_name,"
                    + " sum(b.on_hand) as on_hand,sum(b.reserved) as reserved,"
                    + " sum(b.available) as available,count(*) as bucket_count,"
                    + " count(distinct b.location_id) as location_count"
                    + " from stock_bucket b"
                    + " join warehouse w on w.tenant_id=b.tenant_id and w.id=b.warehouse_id"
                    + " where b.lot_id=? group by b.warehouse_id,w.code,w.name order by w.code",
                lotId)
            .map(
                r ->
                    new LotWarehouseSlice(
                        r.get("warehouse_id", UUID.class),
                        r.get("warehouse_code", String.class),
                        r.get("warehouse_name", String.class),
                        decimal(r, "on_hand"),
                        decimal(r, "reserved"),
                        decimal(r, "available"),
                        r.get("bucket_count", Long.class),
                        r.get("location_count", Long.class)));

    // 库位级：一个 bucket = 一个（库位 × 质量状态），所以行数等于 bucket 数而不是库位数。
    // 同一库位上同批次不同质量状态占两行 —— 那是两批不同的货，合并就没法回答「这批能发多少」。
    var locations =
        sql.fetch(
                "select b.id as bucket_id,b.location_id,loc.code as location_code,"
                    + " loc.name as location_name,loc.type as location_type,"
                    + " loc.pickable as location_pickable,b.quality_status_id,"
                    + " q.code as quality_status_code,q.name as quality_status_name,"
                    + " b.on_hand,b.reserved,b.available,b.last_movement_at"
                    + " from stock_bucket b"
                    + " join warehouse_location loc"
                    + " on loc.tenant_id=b.tenant_id and loc.id=b.location_id"
                    + " join inventory_quality_status q"
                    + " on q.tenant_id=b.tenant_id and q.id=b.quality_status_id"
                    + " where b.lot_id=? order by loc.code,q.code",
                lotId)
            .map(
                r ->
                    new LotLocationSlice(
                        r.get("bucket_id", UUID.class),
                        r.get("location_id", UUID.class),
                        r.get("location_code", String.class),
                        r.get("location_name", String.class),
                        r.get("location_type", String.class),
                        Boolean.TRUE.equals(r.get("location_pickable", Boolean.class)),
                        r.get("quality_status_id", UUID.class),
                        r.get("quality_status_code", String.class),
                        r.get("quality_status_name", String.class),
                        decimal(r, "on_hand"),
                        decimal(r, "reserved"),
                        decimal(r, "available"),
                        r.get("last_movement_at", OffsetDateTime.class)));

    return new LotDistribution(
        header.get("lot_id", UUID.class),
        header.get("lot_code", String.class),
        header.get("sku_id", UUID.class),
        header.get("sku_code", String.class),
        header.get("sku_name", String.class),
        header.get("status", String.class),
        Boolean.TRUE.equals(header.get("expired", Boolean.class)),
        header.get("expires_on", LocalDate.class),
        decimal(header, "on_hand"),
        decimal(header, "reserved"),
        decimal(header, "available"),
        header.get("bucket_count", Long.class),
        header.get("warehouse_count", Long.class),
        header.get("location_count", Long.class),
        warehouses,
        locations);
  }

  // ================================================================ 出入库历史

  /**
   * 批次出入库历史 —— **直接复用 7.1.2.1 台账**，不另写一套流水查询。
   *
   * <p>归属口径（{@code BALANCE_GRANULAR}）与局限随响应一起返回，让调用方不必猜这个数怎么来的。
   * 分录下钻继续走台账自己的端点 {@code /warehouse/inventory/ledger/{transactionId}}。
   *
   * <p>先确认批次在本租户可见：不存在直接 404。否则一个不存在的批次会返回「空流水」，
   * 与「存在但没动过」长得一样。
   */
  public LotMovements movements(UUID lotId, InventoryLedgerModels.InventoryLedgerQuery query) {
    Record lot = sql.fetchOne("select l.id,l.lot_code from inventory_lot l where l.id=?", lotId);
    if (lot == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "批次不存在或无权查看");
    }
    return new LotMovements(
        lot.get("id", UUID.class),
        lot.get("lot_code", String.class),
        "BALANCE_GRANULAR",
        "流水按库存余额行（inventory_balance）记账，而余额行不含批次维度 —— 批次是库存位置"
            + "（stock_bucket）这一层的事实。因此这里列出的是「该批次当前所在库位的余额行上发生过的"
            + "全部流水」：同一余额行下并存多个批次时，其数量无法按批次精确拆分。"
            + "批次库存分布始终是批次精确的。",
        ledger.page(query, lotId));
  }

  // ================================================================ 建立与修改

  /**
   * 建立批次（收货生成与手工建立共用一条入口）。
   *
   * @param tenantId 来自已认证成员关系（控制器传入），**不是**客户端参数。
   *     RLS 的 WITH CHECK 会再用它复核一次，两道都不靠客户端。
   *
   * <p>receivedOn 没传时用 <b>SQL 侧</b>的 {@code current_date} 兜底，而不是
   * {@code LocalDate.now()}：读侧的过期判定同样用 {@code current_date}（数据库当天）。
   * 两边都按 JVM 本地日期算，跨时区部署时「刚建的批次」与「过期判定」会差一天。
   *
   * <p>批号唯一性由 DB 的 {@code UNIQUE(tenant_id,sku_id,lot_code)} 裁决，冲突翻译成 409，
   * 不把 PG 的约束名原样抛给用户。
   */
  public LotArchiveSaved create(UUID tenantId, LotArchiveCreateRequest request) {
    String lotCode = request.lotCode() == null ? "" : request.lotCode().trim();
    if (lotCode.isEmpty()) {
      throw new IllegalArgumentException("批号不能为空");
    }
    requireSku(request.skuId());
    checkDates(request.producedOn(), request.expiresOn());

    LotOrigin origin =
        request.origin() == null || request.origin().isBlank()
            ? LotOrigin.MANUAL
            : LotOrigin.parse(request.origin());
    LotSourceSystem sourceSystem = null;
    if (request.sourceSystem() != null && !request.sourceSystem().isBlank()) {
      sourceSystem = LotSourceSystem.parse(request.sourceSystem());
    }
    // 收货生成的批次必须能指回来源单据，否则「这批货是怎么来的」就断了 —— 而这正是追溯的起点。
    // 手工建的批次允许没有来源（运营补登历史批次就是这种情况）。
    if (origin == LotOrigin.RECEIPT && (sourceSystem == null || request.sourceRefId() == null)) {
      throw new IllegalArgumentException(
          "收货生成的批次必须给出来源系统与来源单据 id（sourceSystem + sourceRefId）");
    }

    var id = UUID.randomUUID();
    try {
      sql.execute(
          "insert into inventory_lot(id,tenant_id,sku_id,lot_code,produced_on,expires_on,received_on,"
              + "origin,source_system,source_ref_id,source_ref_code,supplier_ref_id,supplier_name,notes)"
              + " values(?,?,?,?,?::date,?::date,coalesce(?::date,current_date),?,?,?,?,?,?,?)",
          id,
          tenantId,
          request.skuId(),
          lotCode,
          request.producedOn(),
          request.expiresOn(),
          request.receivedOn(),
          origin.name(),
          sourceSystem == null ? null : sourceSystem.name(),
          request.sourceRefId(),
          orEmpty(fit(request.sourceRefCode(), 64, "来源单号")),
          request.supplierRefId(),
          orEmpty(fit(request.supplierName(), 128, "供应商名称")),
          orEmpty(fit(request.notes(), 500, "批次备注")));
    } catch (org.springframework.dao.DataAccessException e) {
      throw translateLotConflict(e, "建立批次");
    }
    return savedOf(id);
  }

  /**
   * 修改批次属性。
   *
   * <p><b>null = 不修改</b>（部分更新语义），实现上是 {@code coalesce(新值, 旧值)}。
   *
   * <p>刻意不允许改批号、SKU 与 origin：三者都是批次的身份，改了等于换了一个批次，
   * 而 {@code stock_bucket} 上的 {@code lot_id} 还指着旧身份，就地改会让历史无法自洽。
   * 要换批号请新建批次。
   *
   * <p>{@code updated_at} 显式推进：{@code inventory_lot} 上**没有** updated_at 自动触发器
   * （V2 没建），忘了写它就会永远停在建立时间。
   */
  public LotArchiveSaved update(UUID lotId, LotArchiveUpdateRequest request) {
    Record existing =
        sql.fetchOne("select l.id,l.produced_on,l.expires_on from inventory_lot l where l.id=?", lotId);
    if (existing == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "批次不存在或无权查看");
    }
    String status = null;
    if (request.status() != null && !request.status().isBlank()) {
      status = request.status().trim().toUpperCase(Locale.ROOT);
      if (!STATUSES.contains(status)) {
        throw new IllegalArgumentException(
            "未知的批次状态：" + request.status() + "，可选：" + String.join("、", STATUSES));
      }
    }
    // 有效期校验必须拿**合并后**的值：只改生产日期时，拿新值单独比会漏掉「新的生产日期晚于既有失效日」。
    var producedOn =
        request.producedOn() != null ? request.producedOn() : existing.get("produced_on", LocalDate.class);
    var expiresOn =
        request.expiresOn() != null ? request.expiresOn() : existing.get("expires_on", LocalDate.class);
    checkDates(producedOn, expiresOn);

    try {
      sql.execute(
          "update inventory_lot set produced_on=coalesce(?::date,produced_on),"
              + " expires_on=coalesce(?::date,expires_on),status=coalesce(?,status),"
              + " supplier_ref_id=coalesce(?,supplier_ref_id),"
              + " supplier_name=coalesce(?,supplier_name),notes=coalesce(?,notes),"
              + " updated_at=now() where id=?",
          request.producedOn(),
          request.expiresOn(),
          status,
          request.supplierRefId(),
          fit(request.supplierName(), 128, "供应商名称"),
          fit(request.notes(), 500, "批次备注"),
          lotId);
    } catch (org.springframework.dao.DataAccessException e) {
      throw translateLotConflict(e, "修改批次");
    }
    return savedOf(lotId);
  }

  // ================================================================ 条件与排序

  /**
   * 条件拼装。别名后缀让同一段条件在主查询（{@code ""}）与汇总子查询里复用。
   *
   * <p>{@code inStock} 与 {@code warehouseId} 都写成对 {@code stock_bucket} 的 exists：
   * 批次可能一个 bucket 都没有（刚建未收货），用 exists 才是「当前有没有货」，
   * 用 inner join 会把这类批次整批筛掉。
   */
  private void conditions(LotArchiveQuery query, String a, List<String> where, List<Object> binds) {
    if (query.skuId() != null) {
      where.add("l" + a + ".sku_id=?");
      binds.add(query.skuId());
    }
    if (query.warehouseId() != null) {
      where.add(
          "exists (select 1 from stock_bucket wb where wb.tenant_id=l"
              + a
              + ".tenant_id and wb.lot_id=l"
              + a
              + ".id and wb.warehouse_id=?)");
      binds.add(query.warehouseId());
    }
    addEnumIn(where, binds, "l" + a + ".status", query.status(), STATUSES, "批次状态");
    addEnumIn(where, binds, "l" + a + ".origin", query.origin(), ORIGINS, "批次建立方式");
    addEnumIn(
        where, binds, "l" + a + ".source_system", query.sourceSystem(), SOURCE_SYSTEMS, "来源系统");
    if (query.supplierRefId() != null) {
      where.add("l" + a + ".supplier_ref_id=?");
      binds.add(query.supplierRefId());
    }
    if (query.keyword() != null && !query.keyword().isBlank()) {
      where.add(
          "(l"
              + a
              + ".lot_code ilike ? or s"
              + a
              + ".code ilike ? or s"
              + a
              + ".name ilike ? or l"
              + a
              + ".source_ref_code ilike ? or l"
              + a
              + ".supplier_name ilike ?)");
      var k = "%" + query.keyword().trim() + "%";
      for (int i = 0; i < 5; i++) binds.add(k);
    }
    if (query.expiringWithinDays() != null) {
      if (query.expiringWithinDays() < 0) {
        throw new IllegalArgumentException("到期天数不能为负数");
      }
      where.add(
          "l"
              + a
              + ".expires_on is not null and l"
              + a
              + ".expires_on>=current_date and l"
              + a
              + ".expires_on<=current_date+?");
      binds.add(query.expiringWithinDays());
    }
    if (Boolean.TRUE.equals(query.inStock())) {
      where.add(
          "exists (select 1 from stock_bucket sb where sb.tenant_id=l"
              + a
              + ".tenant_id and sb.lot_id=l"
              + a
              + ".id and sb.on_hand>0)");
    }
  }

  /** 多选枚举条件：非法码当场 400，不静默忽略（与 LedgerKind.parse 同一纪律）。 */
  private void addEnumIn(
      List<String> where,
      List<Object> binds,
      String column,
      List<String> values,
      List<String> allowed,
      String label) {
    if (values == null || values.isEmpty()) {
      return;
    }
    var codes = new ArrayList<String>();
    for (var raw : values) {
      if (raw == null || raw.isBlank()) {
        continue;
      }
      var code = raw.trim().toUpperCase(Locale.ROOT);
      if (!allowed.contains(code)) {
        throw new IllegalArgumentException(
            "未知的" + label + "：" + raw + "，可选：" + String.join("、", allowed));
      }
      if (!codes.contains(code)) {
        codes.add(code);
      }
    }
    if (!codes.isEmpty()) {
      where.add(column + " in (" + placeholders(codes.size()) + ")");
      binds.addAll(codes);
    }
  }

  /**
   * 排序。决胜键恒定追加 {@code l.id}：同一失效日期下多批次时翻页不会错位或重复。
   *
   * <p>效期用 {@code nulls last}：无保质期品不该挤在「最早到期」那一列 ——
   * 那会让「哪个批次最紧急」这个判断失真。失效日期升序（越早到期越靠前）。
   */
  private String order(LotArchiveQuery query) {
    String sort = query.sort() == null ? "LOT_CODE" : query.sort().toUpperCase(Locale.ROOT);
    if (!SORTS.contains(sort)) {
      throw new IllegalArgumentException(
          "不支持的排序键：" + query.sort() + "，可选：" + String.join("、", SORTS));
    }
    return switch (sort) {
      case "EXPIRY" -> " order by l.expires_on nulls last,l.lot_code,l.id";
      case "ON_HAND" -> " order by coalesce(agg.on_hand,0) desc,l.lot_code,l.id";
      case "RECEIVED_ON" -> " order by l.received_on desc nulls last,l.lot_code,l.id";
      case "PRODUCED_ON" -> " order by l.produced_on desc nulls last,l.lot_code,l.id";
      case "CREATED_AT" -> " order by l.created_at desc,l.id";
      case "UPDATED_AT" -> " order by l.updated_at desc,l.id";
      default -> " order by s.code,l.lot_code,l.id";
    };
  }

  // ================================================================ 校验与工具

  /** SKU 必须在本租户可见：批次挂在 catalog 的逻辑 ID 上，跨租户塞个 UUID 会建出没人认领的批次。 */
  private void requireSku(UUID skuId) {
    if (skuId == null) {
      throw new IllegalArgumentException("批次必须归属一个 SKU");
    }
    if (sql.fetchOne("select s.id from sku_ref s where s.id=?", skuId) == null) {
      throw new IllegalArgumentException("SKU 不存在或无权访问：" + skuId);
    }
  }

  /** 生产日期不得晚于失效日期：写反的效期会让 7.3.1.2 的 FEFO 排出完全错误的顺序。 */
  private static void checkDates(LocalDate producedOn, LocalDate expiresOn) {
    if (producedOn != null && expiresOn != null && expiresOn.isBefore(producedOn)) {
      throw new IllegalArgumentException(
          "失效日期不能早于生产日期（" + producedOn + " → " + expiresOn + "）");
    }
  }

  /** 长度超限直接报错而不是截断：静默截断会把「供应商名被砍掉一半」藏起来。 */
  private static String fit(String value, int max, String label) {
    if (value != null && value.length() > max) {
      throw new IllegalArgumentException(label + "长度不能超过 " + max + " 个字符");
    }
    return value;
  }

  /**
   * null 落库成空串。
   *
   * <p>{@code source_ref_code} / {@code supplier_name} / {@code notes} 是 {@code NOT NULL DEFAULT ''}：
   * 「没填」与「填了空串」在业务上是同一件事，都该存空串。直接绑 null 会被 PG 挡成
   * not-null violation，而那是数据完整性错误，不是调用方的语义 —— 所以在服务层就归一掉。
   */
  private static String orEmpty(String value) {
    return value == null ? "" : value;
  }

  /**
   * 唯一冲突翻译成 409：不把 PG 的约束名原样抛给用户。
   *
   * <p>捕的是 <b>Spring</b> 的 {@code DataAccessException}（由 jOOQ 走的 DataSource 上的
   * SQL 异常翻译器产出，实际类型是 {@code DuplicateKeyException}），不是 jOOQ 自己的同名类 ——
   * 两者不在同一个包里，接错一个这条 409 就永远不会触发。
   * 23505 = unique_violation，正是 inventory_lot 的 UNIQUE(tenant_id,sku_id,lot_code)。
   */
  private static RuntimeException translateLotConflict(
      org.springframework.dao.DataAccessException e, String action) {
    for (Throwable cause = e; cause != null; cause = cause.getCause()) {
      if (cause instanceof java.sql.SQLException sql && "23505".equals(sql.getSQLState())) {
        return new ResponseStatusException(
            HttpStatus.CONFLICT, "同 SKU 下已存在该批号，" + action + "失败");
      }
    }
    return e;
  }

  /** 建立/修改后回读整行：响应是「库里真实存了什么」，不是「我们以为存了什么」。 */
  private LotArchiveSaved savedOf(UUID lotId) {
    Record r =
        sql.fetchOne(
            "select l.id as lot_id,l.sku_id,l.lot_code,l.produced_on,l.expires_on,l.received_on,"
                + " l.origin,l.status,l.source_system,l.source_ref_id,l.source_ref_code,"
                + " l.supplier_ref_id,l.supplier_name,l.created_at,l.updated_at,"
                + " (l.expires_on is not null and l.expires_on<current_date) as expired"
                + " from inventory_lot l where l.id=?",
            lotId);
    if (r == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "批次不存在或无权查看");
    }
    return new LotArchiveSaved(
        r.get("lot_id", UUID.class),
        r.get("lot_code", String.class),
        r.get("sku_id", UUID.class),
        r.get("origin", String.class),
        r.get("status", String.class),
        r.get("produced_on", LocalDate.class),
        r.get("expires_on", LocalDate.class),
        r.get("received_on", LocalDate.class),
        r.get("source_system", String.class),
        r.get("source_ref_id", UUID.class),
        r.get("source_ref_code", String.class),
        r.get("supplier_ref_id", UUID.class),
        r.get("supplier_name", String.class),
        Boolean.TRUE.equals(r.get("expired", Boolean.class)),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  private static LotArchiveRow row(Record r) {
    return new LotArchiveRow(
        r.get("lot_id", UUID.class),
        r.get("sku_id", UUID.class),
        r.get("sku_code", String.class),
        r.get("sku_name", String.class),
        r.get("lot_code", String.class),
        r.get("produced_on", LocalDate.class),
        r.get("expires_on", LocalDate.class),
        r.get("received_on", LocalDate.class),
        r.get("origin", String.class),
        r.get("source_system", String.class),
        r.get("source_ref_id", UUID.class),
        r.get("source_ref_code", String.class),
        r.get("supplier_ref_id", UUID.class),
        r.get("supplier_name", String.class),
        r.get("status", String.class),
        Boolean.TRUE.equals(r.get("expired", Boolean.class)),
        daysToExpiry(r),
        decimal(r, "on_hand"),
        decimal(r, "reserved"),
        decimal(r, "available"),
        r.get("bucket_count", Long.class),
        r.get("location_count", Long.class),
        r.get("warehouse_count", Long.class),
        r.get("notes", String.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  private static BigDecimal decimal(Record r, String field) {
    var v = r.get(field, BigDecimal.class);
    return v == null ? BigDecimal.ZERO : v;
  }

  /**
   * 取「距离失效日期还有几天」。
   *
   * <p>{@code current_date - expires_on} 在 PG 里是 integer，但**纯文本语句里 jOOQ 拿不到列类型**，
   * 直接 {@code r.get(field, Integer.class)} 会因类型不匹配而返回 null —— 于是每一行都显示成
   * 「没有效期」，而这恰恰是最该显示出来的那个字段。这里收口成不受驱动返回类型影响的读法。
   *
   * <p>过期批次数为负，无失效日期为 null。
   */
  private static Integer daysToExpiry(Record r) {
    var value = r.get("days_to_expiry");
    if (value == null) {
      return null;
    }
    if (value instanceof Number number) {
      return number.intValue();
    }
    return Integer.valueOf(value.toString());
  }

  private static String placeholders(int count) {
    return String.join(",", java.util.Collections.nCopies(count, "?"));
  }

  private static List<Object> append(List<Object> binds, Object... more) {
    var all = new ArrayList<>(binds);
    all.addAll(Arrays.asList(more));
    return all;
  }
}
