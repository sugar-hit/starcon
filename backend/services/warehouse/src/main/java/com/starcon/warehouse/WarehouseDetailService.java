package com.starcon.warehouse;

import static com.starcon.warehouse.WarehouseDetailModels.*;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 8.1.1.2 仓库详情 —— 仓库的控制塔。
 *
 * <p>蓝图：「单仓库全景：基础信息与地址、库位结构、库存分布、作业统计、关联的
 * 自发货/中转/FBA 备货策略、作业人员与设备。」「详情页是仓库的控制塔，
 * 作业入口与库存入口都在这里。」
 *
 * <p><b>纯聚合视图，一个新表都不建。</b>基础信息来自 {@code warehouse}（地址五列由 V77 补），
 * 库存分布来自 {@code inventory_balance} 与 {@code stock_bucket}，库位结构来自
 * {@code warehouse_location}，作业统计来自 {@code goods_receipt} / {@code stocktake_task} /
 * {@code transfer_order} / {@code inventory_entry`，人员来自 {@code warehouse_work_task}。
 * 「合计 == 逐行求和」因此不是靠约定成立，而是结构上无从违反 —— 没有第二份数据可以漂移。
 *
 * <p><b>三组纪律，逐条都在类型层再钉一次：</b>
 *
 * <ol>
 *   <li>平台仓观测值与自有账本**永不相加**（V55 的两条触发器 + {@code StockDistribution.platform} 独立字段）
 *   <li>「未交付」与「有但是 0」是两种形状：没有表的维度走
 *       {@code available=false} + {@code ownerModule}，不给 0、不给空数组
 *   <li>租户谓词逐条查询都写：RLS 是第一道防线，显式 {@code tenant_id=} 是第二道。
 *       忘了绑定上下文时第二道让查询当场返回空，而不是全表
 * </ol>
 */
@Service
public class WarehouseDetailService {

  /** 单页最大行数，与 8.1.1.1 同一个上限。 */
  static final int MAX_PAGE_SIZE = 200;

  /** 库位排序键白名单：排序键来自 query string，只允许这五个。 */
  static final List<String> LOCATION_SORTS = List.of("CODE", "NAME", "TYPE", "STOCK", "MOVEMENT");

  /** 状态词表与 V1 的 CHECK 逐字一致。ARCHIVED 是终态，不可回退。 */
  private static final List<String> STATUSES = List.of("ACTIVE", "INACTIVE", "ARCHIVED");

  private static final DateTimeFormatter LOCAL_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

  /** 库位类型词表与 V2 的 CHECK 逐字一致。 */
  private static final List<String> LOCATION_TYPES =
      List.of("STORAGE", "RECEIVING", "SHIPPING", "QUARANTINE", "DEFECTIVE", "FROZEN");

  private final DSLContext sql;
  private final Clock clock;

  public WarehouseDetailService(DSLContext sql) {
    this.sql = sql;
    this.clock = Clock.systemDefaultZone();
  }

  // ================================================================ 详情

  /** 单仓库全景。仓库不存在或不属于本租户一律 404（两者的可观测结果必须相同）。 */
  public WarehouseDetail detail(UUID warehouseId) {
    WarehouseBase base = base(warehouseId);
    WarehouseFacilityModels.PlatformStock platform =
        base.kind().platformObserved() ? platformStock(warehouseId) : null;
    return new WarehouseDetail(
        base,
        address(base),
        stock(warehouseId, platform),
        locationStructure(warehouseId),
        work(warehouseId),
        strategies(warehouseId, platform),
        labor(warehouseId),
        entryPoints(),
        "库存分布与作业统计来自本服务已落库的事实表；平台仓观测值与自有账本不相加，"
            + "未交付的维度在各入口上标注为「未接入」并写明归属模块。");
  }

  private WarehouseBase base(UUID warehouseId) {
    var r =
        sql.fetchOne(
            """
            select w.id, w.code, w.name, w.kind, w.country_code, w.timezone, w.status, w.is_default,
                   w.default_legal_entity_id, w.created_at, w.updated_at,
                   w.address_line1, w.address_line2, w.city, w.region, w.postal_code,
                   le.code as le_code, le.name as le_name,
                   (select count(distinct b.legal_entity_id) from inventory_balance b
                     where b.tenant_id=w.tenant_id and b.warehouse_id=w.id) as legal_entity_count,
                   (select count(distinct b.sku_id) from inventory_balance b
                     where b.tenant_id=w.tenant_id and b.warehouse_id=w.id) as sku_count
              from warehouse w
              left join legal_entity_ref le
                on le.tenant_id=w.tenant_id and le.id=w.default_legal_entity_id
             where w.tenant_id = current_setting('app.current_tenant', true)::uuid
               and w.id = ?
            """,
            warehouseId);
    if (r == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "仓库不存在");
    WarehouseFacilityModels.WarehouseKind kind =
        WarehouseFacilityModels.WarehouseKind.valueOf(r.get("kind", String.class));
    String status = r.get("status", String.class);
    return new WarehouseBase(
        r.get("id", UUID.class),
        r.get("code", String.class),
        r.get("name", String.class),
        kind,
        kind.label(),
        r.get("country_code", String.class),
        r.get("timezone", String.class),
        localTime(r.get("timezone", String.class)),
        status,
        statusLabel(status),
        Boolean.TRUE.equals(r.get("is_default", Boolean.class)),
        r.get("default_legal_entity_id", UUID.class),
        r.get("le_code", String.class),
        r.get("le_name", String.class),
        r.get("legal_entity_count", Long.class),
        r.get("sku_count", Long.class),
        r.get("created_at", java.time.OffsetDateTime.class),
        r.get("updated_at", java.time.OffsetDateTime.class));
  }

  /** 仓库当地墙上时间。库里时区无法识别时返 null，绝不静默退化成 UTC。 */
  private String localTime(String timezone) {
    try {
      return LocalDateTime.ofInstant(clock.instant(), ZoneId.of(timezone)).format(LOCAL_TIME);
    } catch (DateTimeException e) {
      return null;
    }
  }

  // ------------------------------------------------------------ 地址

  /**
   * 地址。
   *
   * <p>五个字段全部可空（V77），所以这里显式报出**缺哪几个**而不是让界面显示一行空白：
   * 「境外仓没有邮编」与「这家仓压根没填地址」必须能一眼分开。
   */
  private WarehouseAddress address(WarehouseBase base) {
    var r =
        sql.fetchOne(
            "select address_line1,address_line2,city,region,postal_code from warehouse"
                + " where tenant_id = current_setting('app.current_tenant', true)::uuid and id=?",
            base.id());
    String line1 = r.get("address_line1", String.class);
    String line2 = r.get("address_line2", String.class);
    String city = r.get("city", String.class);
    String region = r.get("region", String.class);
    String postal = r.get("postal_code", String.class);

    var missing = new ArrayList<String>();
    if (isBlank(line1)) missing.add("街道地址");
    if (isBlank(city)) missing.add("城市");
    if (isBlank(region)) missing.add("省/州");
    if (isBlank(postal)) missing.add("邮编");

    var text = new ArrayList<String>();
    if (!isBlank(line1)) text.add(line1);
    if (!isBlank(line2)) text.add(line2);
    if (!isBlank(region)) text.add(region);
    if (!isBlank(city)) text.add(city);
    if (!isBlank(postal)) text.add(postal);
    text.add(base.countryCode());

    return new WarehouseAddress(
        line1, line2, city, region, postal, base.countryCode(), missing,
        text.isEmpty() ? null : String.join(" ", text));
  }

  // ------------------------------------------------------------ 库存分布

  private StockDistribution stock(
      UUID warehouseId, WarehouseFacilityModels.PlatformStock platform) {
    var totals =
        sql.fetchOne(
            """
            select coalesce(sum(on_hand),0) as on_hand,
                   coalesce(sum(reserved),0) as reserved,
                   coalesce(sum(available),0) as available,
                   count(distinct sku_id) as sku_count
              from inventory_balance
             where tenant_id = current_setting('app.current_tenant', true)::uuid
               and warehouse_id = ?
            """,
            warehouseId);

    long bucketCount =
        sql.fetchOne(
                "select count(*) from stock_bucket"
                    + " where tenant_id = current_setting('app.current_tenant', true)::uuid"
                    + " and warehouse_id = ?",
                warehouseId)
            .get(0, Long.class);

    // 质量状态切面列出本租户**全部**质量状态（含当前无货的），
    // 因为「这个租户有哪些质量口径」本身就是详情页要回答的问题；
    // 把 0 行藏起来会让人以为该口径不存在。
    var byQuality =
        sql.fetch(
                """
                select q.id, q.code, q.name, q.role,
                       coalesce(sum(b.on_hand),0) as on_hand,
                       coalesce(sum(b.reserved),0) as reserved,
                       coalesce(sum(b.available),0) as available,
                       count(distinct b.sku_id) as sku_count
                  from inventory_quality_status q
                  left join inventory_balance b
                    on b.tenant_id=q.tenant_id
                   and b.quality_status_id=q.id
                   and b.warehouse_id=?
                 where q.tenant_id = current_setting('app.current_tenant', true)::uuid
                   and q.enabled
                 group by q.id, q.code, q.name, q.role
                 order by q.role, q.code
                """,
                warehouseId)
            .map(
                r ->
                    new QualitySlice(
                        r.get("id", UUID.class),
                        r.get("code", String.class),
                        r.get("name", String.class),
                        r.get("role", String.class),
                        r.get("on_hand", BigDecimal.class),
                        r.get("reserved", BigDecimal.class),
                        r.get("available", BigDecimal.class),
                        r.get("sku_count", Long.class)));

    // 货权法人切面用 inner join：只列**真的在这个仓持有货**的法人。
    // 没有货的法人对「库存分布」没有信息量（它属于绑定法人那一栏，已在 base 里）。
    var byEntity =
        sql.fetch(
                """
                select le.id, le.code, le.name,
                       coalesce(sum(b.on_hand),0) as on_hand,
                       count(distinct b.sku_id) as sku_count
                  from inventory_balance b
                  join legal_entity_ref le
                    on le.tenant_id=b.tenant_id and le.id=b.legal_entity_id
                 where b.tenant_id = current_setting('app.current_tenant', true)::uuid
                   and b.warehouse_id = ?
                 group by le.id, le.code, le.name
                 order by le.code
                """,
                warehouseId)
            .map(
                r ->
                    new LegalEntitySlice(
                        r.get("id", UUID.class),
                        r.get("code", String.class),
                        r.get("name", String.class),
                        r.get("on_hand", BigDecimal.class),
                        r.get("sku_count", Long.class)));

    return new StockDistribution(
        totals.get("on_hand", BigDecimal.class),
        totals.get("reserved", BigDecimal.class),
        totals.get("available", BigDecimal.class),
        totals.get("sku_count", Long.class),
        bucketCount,
        byQuality,
        byEntity,
        locationTypeSlice(warehouseId),
        platform);
  }

  /**
   * 平台仓观测值。每个 {@code (仓库, SKU, 来源)} 只取最新一条 ——
   * platform_stock_observation 是 append-only 历史序列（V55），求和会得到 300+310=610 这个不存在的数。
   *
   * <p>自有仓恒不调用本方法（由 {@link #detail} 的 {@code platformObserved()} 裁决）：
   * 「这个来源不存在」与「观测到 0 件」在界面上含义完全不同。
   */
  private WarehouseFacilityModels.PlatformStock platformStock(UUID warehouseId) {
    var r =
        sql.fetchOne(
            """
            select count(*) as source_count,
                   coalesce(sum(observed_on_hand),0) as observed_on_hand,
                   coalesce(sum(observed_available),0) as observed_available,
                   coalesce(sum(observed_inbound),0) as observed_inbound,
                   max(collected_at) as latest_collected_at
              from platform_stock_observation o
             where o.tenant_id = current_setting('app.current_tenant', true)::uuid
               and o.warehouse_id = ?
               and o.id in (select distinct on (sku_id,source) id
                              from platform_stock_observation
                             where tenant_id = current_setting('app.current_tenant', true)::uuid
                               and warehouse_id = ?
                             order by sku_id,source,collected_at desc)
            """,
            warehouseId, warehouseId);
    if (r == null || r.get("source_count", Long.class) == 0L) return null;
    return new WarehouseFacilityModels.PlatformStock(
        r.get("source_count", Long.class),
        r.get("observed_on_hand", BigDecimal.class),
        r.get("observed_available", BigDecimal.class),
        r.get("observed_inbound", BigDecimal.class),
        r.get("latest_collected_at", java.time.OffsetDateTime.class));
  }

  // ------------------------------------------------------------ 库位结构

  /**
   * 库位结构。
   *
   * <p>{@code warehouse_location} 表**确实存在**（V2，带 type / status / pickable），
   * 所以库位数量、类型分布、可拣库位数都是真实数据。没交付的是**层级**：
   * 表上没有 {@code parent_id}，也没有容量与承重列 —— 那是 8.1.2.1 的事，
   * 在 {@link #entryPoints()} 里报 {@code available=false}。
   */
  private LocationStructure locationStructure(UUID warehouseId) {
    var r =
        sql.fetchOne(
            """
            select count(*) as total_count,
                   count(*) filter (where l.status='ACTIVE') as active_count,
                   count(*) filter (where l.pickable) as pickable_count,
                   count(*) filter (where exists (
                        select 1 from stock_bucket b
                         where b.tenant_id=l.tenant_id and b.location_id=l.id and b.on_hand>0
                   )) as occupied_count,
                   coalesce(sum((select coalesce(sum(b.on_hand),0) from stock_bucket b
                                 where b.tenant_id=l.tenant_id and b.location_id=l.id)),0) as on_hand
              from warehouse_location l
             where l.tenant_id = current_setting('app.current_tenant', true)::uuid
               and l.warehouse_id = ?
            """,
            warehouseId);
    return new LocationStructure(
        r.get("total_count", Long.class),
        r.get("active_count", Long.class),
        r.get("pickable_count", Long.class),
        r.get("occupied_count", Long.class),
        r.get("on_hand", BigDecimal.class),
        locationTypeSlice(warehouseId));
  }

  /**
   * 按库位类型的分布。
   *
   * <p>{@link StockDistribution#byLocationType()} 与 {@link LocationStructure#byType()} 是**同一份
   * 事实的同一段 SQL**，故意调用两次而不是让调用方互相传值：两处返回同一批行，
   * 库存分布与库位结构两个面板因此不可能各算各的。
   */
  private List<LocationTypeSlice> locationTypeSlice(UUID warehouseId) {
    return sql.fetch(
            """
            select l.type,
                   count(distinct l.id) as location_count,
                   count(distinct l.id) filter (where l.pickable) as pickable_count,
                   coalesce(sum(b.on_hand),0) as on_hand
              from warehouse_location l
              left join stock_bucket b
                on b.tenant_id=l.tenant_id and b.location_id=l.id
             where l.tenant_id = current_setting('app.current_tenant', true)::uuid
               and l.warehouse_id = ?
             group by l.type
             order by l.type
            """,
            warehouseId)
        .map(
            r ->
                new LocationTypeSlice(
                    r.get("type", String.class),
                    locationTypeLabel(r.get("type", String.class)),
                    r.get("location_count", Long.class),
                    r.get("pickable_count", Long.class),
                    r.get("on_hand", BigDecimal.class)));
  }

  // ------------------------------------------------------------ 作业统计

  /**
   * 作业统计。
   *
   * <p>四个来源各自收口后相加，**不** join —— join 会让一张调拨单同时命中 from 与 to 两行而翻倍。
   * 上架（8.2.2.1）、拣货出库（8.3）、组装（8.4）没有表，所以不在这个合计里，
   * 它们在 {@link #entryPoints()} 里报 available=false，**不算成 0 冒充「这个仓不作业」**。
   */
  private WorkStatistics work(UUID warehouseId) {
    var r =
        sql.fetchOne(
            """
            select (select count(*) from goods_receipt r
                      where r.tenant_id = current_setting('app.current_tenant', true)::uuid
                        and r.warehouse_id = ?) as receipt_total,
                   (select count(*) from goods_receipt r
                      where r.tenant_id = current_setting('app.current_tenant', true)::uuid
                        and r.warehouse_id = ? and r.status <> 'CLOSED') as receipt_open,
                   (select count(*) from goods_receipt r
                      where r.tenant_id = current_setting('app.current_tenant', true)::uuid
                        and r.warehouse_id = ? and r.status = 'CLOSED') as receipt_closed,
                   (select coalesce(sum(l.received_quantity),0) from goods_receipt_line l
                      join goods_receipt r on r.tenant_id=l.tenant_id and r.id=l.receipt_id
                     where r.tenant_id = current_setting('app.current_tenant', true)::uuid
                       and r.warehouse_id = ?) as receipt_quantity,
                   (select count(*) from stocktake_task t
                      join stocktake_sheet sh on sh.tenant_id=t.tenant_id and sh.id=t.sheet_id
                     where t.tenant_id = current_setting('app.current_tenant', true)::uuid
                       and ((sh.scope_type='WAREHOUSE' and sh.scope_key=?)
                         or (sh.scope_type='LOCATION' and exists (
                              select 1 from warehouse_location l
                               where l.tenant_id=sh.tenant_id and l.id=sh.scope_key
                                 and l.warehouse_id=?)))) as task_count,
                   (select count(*) from stocktake_task t
                      join stocktake_sheet sh on sh.tenant_id=t.tenant_id and sh.id=t.sheet_id
                     where t.tenant_id = current_setting('app.current_tenant', true)::uuid
                       and sh.status <> 'COUNTED'
                       and ((sh.scope_type='WAREHOUSE' and sh.scope_key=?)
                         or (sh.scope_type='LOCATION' and exists (
                              select 1 from warehouse_location l
                               where l.tenant_id=sh.tenant_id and l.id=sh.scope_key
                                 and l.warehouse_id=?)))) as task_open,
                   (select count(*) from transfer_order o
                     where o.tenant_id = current_setting('app.current_tenant', true)::uuid
                       and o.to_warehouse_id=?) as transfer_in,
                   (select count(*) from transfer_order o
                     where o.tenant_id = current_setting('app.current_tenant', true)::uuid
                       and o.from_warehouse_id=?) as transfer_out,
                   (select count(*) from transfer_order o
                     where o.tenant_id = current_setting('app.current_tenant', true)::uuid
                       and o.to_warehouse_id=? and o.status <> 'RECEIVED') as transfer_in_open,
                   (select count(*) from transfer_order o
                     where o.tenant_id = current_setting('app.current_tenant', true)::uuid
                       and o.from_warehouse_id=? and o.status <> 'RECEIVED') as transfer_out_open,
                   (select count(*) from inventory_entry e
                      join inventory_balance b on b.tenant_id=e.tenant_id and b.id=e.balance_id
                     where e.tenant_id = current_setting('app.current_tenant', true)::uuid
                       and b.warehouse_id=?) as movement_count
            """,
            warehouseId, warehouseId, warehouseId, warehouseId,
            warehouseId, warehouseId, warehouseId, warehouseId,
            warehouseId, warehouseId, warehouseId, warehouseId, warehouseId);
    long receiptTotal = r.get("receipt_total", Long.class);
    long taskCount = r.get("task_count", Long.class);
    long transferIn = r.get("transfer_in", Long.class);
    long transferOut = r.get("transfer_out", Long.class);
    long open =
        r.get("receipt_open", Long.class)
            + r.get("task_open", Long.class)
            + r.get("transfer_in_open", Long.class)
            + r.get("transfer_out_open", Long.class);
    return new WorkStatistics(
        receiptTotal,
        r.get("receipt_open", Long.class),
        r.get("receipt_closed", Long.class),
        r.get("receipt_quantity", BigDecimal.class),
        taskCount,
        transferIn,
        transferOut,
        r.get("movement_count", Long.class),
        receiptTotal + taskCount + transferIn + transferOut,
        open);
  }

  // ------------------------------------------------------------ 备货策略

  /**
   * 自发货 / 中转 / FBA 三条备货策略。
   *
   * <p>三条的**配置**都属 8.1.1.3 仓库配置（批次序列号开关、拣货策略、复核比例、拆零发货、
   * 工作时段），本模块未交付，所以 {@code available=false}。
   * 但每条背后**已经有真实事实**：调拨单、在途腿与到货事件、平台观测都能真算出来，
   * 所以 {@link StrategyFacts} 给实数 —— 「没有这条策略」与「这条策略下现在没货」是两件事。
   */
  private List<StrategyBinding> strategies(
      UUID warehouseId, WarehouseFacilityModels.PlatformStock platform) {
    var counts =
        sql.fetchOne(
            """
            select (select count(*) from transfer_order o
                      where o.tenant_id = current_setting('app.current_tenant', true)::uuid
                        and o.from_warehouse_id=?) as transfer_out,
                   (select count(*) from transfer_order o
                      where o.tenant_id = current_setting('app.current_tenant', true)::uuid
                        and o.to_warehouse_id=?) as transfer_in,
                   (select count(*) from inventory_transfer t
                      where t.tenant_id = current_setting('app.current_tenant', true)::uuid
                        and t.from_warehouse_id=? and t.status='IN_TRANSIT') as transit_out,
                   (select count(*) from in_transit_event e
                      join inventory_transfer t on t.tenant_id=e.tenant_id and t.id=e.leg_id
                     where e.tenant_id = current_setting('app.current_tenant', true)::uuid
                       and t.from_warehouse_id=?) as arrival_event
            """,
            warehouseId, warehouseId, warehouseId, warehouseId);
    var selfFulfillment =
        new StrategyFacts(
            counts.get("transfer_out", Long.class),
            counts.get("transfer_in", Long.class),
            0L,
            null,
            null,
            "自发货能力由本仓既有库存与调拨事实体现；拣货策略与拆零发货开关属 8.1.1.3，未交付。");
    var crossDock =
        new StrategyFacts(
            counts.get("transfer_out", Long.class),
            counts.get("transfer_in", Long.class),
            counts.get("transit_out", Long.class),
            null,
            null,
            "中转在途取 inventory_transfer 中本仓为起点的未达腿，到货事件 "
                + counts.get("arrival_event", Long.class) + " 笔；中转策略参数属 8.1.1.3，未交付。");
    var fba =
        new StrategyFacts(
            0L,
            0L,
            0L,
            platform == null ? null : platform.observedOnHand(),
            platform == null ? null : platform.sourceCount(),
            platform == null
                ? "本仓没有平台观测记录：FBA 备货策略属 8.1.1.3，未交付。"
                : "平台观测在手来自 platform_stock_observation 最新一条，与自有账本不相加；"
                    + "FBA 备货策略属 8.1.1.3，未交付。");

    return List.of(
        new StrategyBinding(
            "SELF_FULFILLMENT", "自发货", false, "8.1.1.3",
            "自发货策略（拣货策略、默认复核比例、是否允许拆零发货、工作时段与班次）属仓库配置 8.1.1.3，尚未交付。",
            null, selfFulfillment),
        new StrategyBinding(
            "CROSS_DOCK", "中转", false, "8.1.1.3",
            "中转策略参数属仓库配置 8.1.1.3，尚未交付；下方的在途腿与到货事件是已落库的真实事实。",
            "/warehouse/transfers", crossDock),
        new StrategyBinding(
            "FBA_STOCKING", "FBA 备货", false, "8.1.1.3",
            "FBA 备货策略（补货节奏、安全库存）属仓库配置 8.1.1.3，尚未交付；下方的观测在手是平台报出的观测值。",
            null, fba));
  }

  // ------------------------------------------------------------ 人员与设备

  /**
   * 作业人员与设备。
   *
   * <p>三个子维度分别表态，因为交付状态真的不一样：
   * 作业任务与派工已接入（{@code warehouse_work_task}，V74 / 8.5.1.1）；
   * 花名册与排班没有表；设备表 {@code print_device}（V73）**没有 warehouse_id** ——
   * 设备是租户级的，与仓库之间不存在绑定关系，所以按仓统计设备数在数据层就无从谈起。
   */
  private LaborResources labor(UUID warehouseId) {
    var r =
        sql.fetchOne(
            """
            select count(*) as task_total,
                   count(*) filter (where operator_user_id is null) as unassigned,
                   count(*) filter (where status='RUNNING') as running,
                   count(*) filter (where status='COMPLETED') as completed,
                   count(*) filter (where status='EXCEPTION') as exception,
                   count(distinct operator_user_id) as operators
              from warehouse_work_task
             where tenant_id = current_setting('app.current_tenant', true)::uuid
               and warehouse_id = ?
            """,
            warehouseId);
    var byWorkType =
        sql.fetch(
                """
                select work_type,
                       count(*) as task_count,
                       count(*) filter (where status='COMPLETED') as completed_count
                  from warehouse_work_task
                 where tenant_id = current_setting('app.current_tenant', true)::uuid
                   and warehouse_id = ?
                 group by work_type
                 order by work_type
                """,
                warehouseId)
            .map(
                row ->
                    new WorkTypeSlice(
                        row.get("work_type", String.class),
                        workTypeLabel(row.get("work_type", String.class)),
                        row.get("task_count", Long.class),
                        row.get("completed_count", Long.class)));
    return new LaborResources(
        true,
        "8.5.1.1",
        "已接入 warehouse_work_task：派工、在跑、完成与异常都是真实计数。",
        r.get("task_total", Long.class),
        r.get("unassigned", Long.class),
        r.get("running", Long.class),
        r.get("completed", Long.class),
        r.get("exception", Long.class),
        r.get("operators", Long.class),
        byWorkType,
        false,
        "8.1.1.3",
        "人员花名册与排班没有数据表（工作时段与班次属 8.1.1.3 配置），"
            + "所以只能报派工与在岗任务，不能报「这个仓有几个人」。",
        false,
        "8.3.4.2",
        "print_device 表存在但**没有 warehouse_id**：设备是租户级的，设备与仓库之间"
            + "没有绑定关系，按仓统计设备数在数据层就无从谈起，因此如实报未接入。");
  }

  // ================================================================ 库位清单

  /** 库位清单：真分页 + 排序白名单 + 筛选，合计不受分页与筛选影响。 */
  public LocationPage locations(UUID warehouseId, LocationQuery q) {
    requireWarehouse(warehouseId);
    int size = Math.min(MAX_PAGE_SIZE, Math.max(1, q.size() == null ? 20 : q.size()));
    int page = Math.max(0, q.page() == null ? 0 : q.page());

    var where = new ArrayList<String>();
    var binds = new ArrayList<Object>();
    filters(q, where, binds);
    String sqlWhere = where.isEmpty() ? "" : " where " + String.join(" and ", where);

    long total =
        sql.fetchOne(
                "select count(*) from warehouse_location l" + sqlWhere, binds.toArray())
            .get(0, Long.class);

    var rows =
        sql.fetch(
                selectLocations() + sqlWhere + orderBy(q.sort()) + " limit ? offset ?",
                bindsToArray(binds, size, page * size))
            .map(WarehouseDetailService::locationRow);

    return new LocationPage(locationTotals(warehouseId), rows, total, page, size,
        "合计不受分页与筛选影响；库位上的数量来自 stock_bucket，与仓库级 inventory_balance 是同一批货的两种视角。");
  }

  private String selectLocations() {
    return """
        select l.id, l.code, l.name, l.type, l.status, l.pickable,
               coalesce(b.on_hand,0) as on_hand,
               coalesce(b.reserved,0) as reserved,
               coalesce(b.available,0) as available,
               coalesce(b.sku_count,0) as sku_count,
               coalesce(b.bucket_count,0) as bucket_count,
               b.last_movement_at
        from warehouse_location l
        left join (
            select location_id,
                   sum(on_hand) as on_hand, sum(reserved) as reserved, sum(available) as available,
                   count(distinct sku_id) as sku_count, count(*) as bucket_count,
                   max(last_movement_at) as last_movement_at
              from stock_bucket
             where tenant_id = current_setting('app.current_tenant', true)::uuid
             group by location_id
        ) b on b.location_id = l.id
        """;
  }

  private static void filters(LocationQuery q, List<String> where, List<Object> binds) {
    where.add("l.tenant_id = current_setting('app.current_tenant', true)::uuid");
    if (q.type() != null && !q.type().isBlank()) {
      String type = q.type().trim().toUpperCase(Locale.ROOT);
      if (!LOCATION_TYPES.contains(type)) throw new IllegalArgumentException("不支持的库位类型：" + q.type());
      where.add("l.type = ?");
      binds.add(type);
    }
    if (q.status() != null && !q.status().isBlank()) {
      String status = q.status().trim().toUpperCase(Locale.ROOT);
      if (!List.of("ACTIVE", "INACTIVE").contains(status))
        throw new IllegalArgumentException("不支持的库位状态：" + q.status());
      where.add("l.status = ?");
      binds.add(status);
    }
    if (q.keyword() != null && !q.keyword().isBlank()) {
      where.add("(l.code ilike ? or l.name ilike ?)");
      String like = "%" + q.keyword().trim() + "%";
      binds.add(like);
      binds.add(like);
    }
  }

  private String orderBy(String sort) {
    String key = sort == null || sort.isBlank() ? "CODE" : sort.toUpperCase(Locale.ROOT);
    if (!LOCATION_SORTS.contains(key)) throw new IllegalArgumentException("不支持的排序键：" + sort);
    // 一律以 code 收尾：排序键相同时必须有确定次序，否则同一页两次请求可能给出不同顺序。
    return switch (key) {
      case "NAME" -> " order by l.name, l.code";
      case "TYPE" -> " order by l.type, l.code";
      case "STOCK" -> " order by coalesce(b.on_hand,0) desc, l.code";
      case "MOVEMENT" -> " order by b.last_movement_at desc nulls last, l.code";
      default -> " order by l.code";
    };
  }

  private LocationTotals locationTotals(UUID warehouseId) {
    var r =
        sql.fetchOne(
            """
            select count(*) as location_count,
                   count(*) filter (where l.status='ACTIVE') as active_count,
                   count(*) filter (where l.pickable) as pickable_count,
                   count(*) filter (where exists (
                        select 1 from stock_bucket b
                         where b.tenant_id=l.tenant_id and b.location_id=l.id and b.on_hand>0
                   )) as occupied_count,
                   coalesce(sum((select coalesce(sum(b.on_hand),0) from stock_bucket b
                                 where b.tenant_id=l.tenant_id and b.location_id=l.id)),0) as on_hand
              from warehouse_location l
             where l.tenant_id = current_setting('app.current_tenant', true)::uuid
               and l.warehouse_id = ?
            """,
            warehouseId);
    return new LocationTotals(
        r.get("location_count", Long.class),
        r.get("active_count", Long.class),
        r.get("pickable_count", Long.class),
        r.get("occupied_count", Long.class),
        r.get("on_hand", BigDecimal.class));
  }

  private static LocationRow locationRow(org.jooq.Record r) {
    String type = r.get("type", String.class);
    return new LocationRow(
        r.get("id", UUID.class),
        r.get("code", String.class),
        r.get("name", String.class),
        type,
        locationTypeLabel(type),
        r.get("status", String.class),
        Boolean.TRUE.equals(r.get("pickable", Boolean.class)),
        r.get("on_hand", BigDecimal.class),
        r.get("reserved", BigDecimal.class),
        r.get("available", BigDecimal.class),
        r.get("sku_count", Long.class),
        r.get("bucket_count", Long.class),
        r.get("last_movement_at", java.time.OffsetDateTime.class));
  }

  // ================================================================ 子模块入口

  /**
   * 各子模块入口。
   *
   * <p>蓝图把「各子模块入口」写进了本模块的输出，所以它是交付物本身。
   * {@code available=false} 的入口 {@code href} 为 null —— 前端据此禁用按钮，
   * 而不是跳到一个不存在的路由（与 8.1.1.1 交付前禁用详情入口同一条纪律）。
   *
   * <p>已交付的 href 全部指向**本 worktree 里真实存在的 page.tsx**，不指向蓝图里规划但
   * 尚未建页的路由。
   */
  public List<DetailEntryPoint> entryPoints() {
    return List.of(
        new DetailEntryPoint("INVENTORY", "库存明细", "7.1.1.2", true, "/inventory",
            "按库位 / 批次 / 质量状态查看这一个仓的库存明细与流水。"),
        new DetailEntryPoint("TRANSFER", "仓间调拨", "7.5.1.1", true, "/warehouse/transfers",
            "调入与调出本仓的调拨单与在途腿。"),
        new DetailEntryPoint("RECEIVING", "收货单", "8.2.1.1", true, "/warehouse/receipts",
            "本仓的收货单、实收明细与差异记录。"),
        new DetailEntryPoint("LOCATION_TREE", "库位树", "8.1.2.1", false, null,
            "warehouse_location 表有库位档案但**没有层级与容量列**（无 parent_id / 承重），"
                + "「区 → 巷 → 排 → 架 → 层 → 格」这棵树属 8.1.2.1，尚未交付。"),
        new DetailEntryPoint("LOCATION_ALLOCATION", "库位自动分配", "8.1.2.2", false, null,
            "按品类聚类与容量承重匹配推荐库位属 8.1.2.2，尚未交付。"),
        new DetailEntryPoint("PUTAWAY", "上架任务", "8.2.2.1", false, null,
            "goods_receipt 里已有待检 / 可售分桶，但**上架任务表不存在**，尚未交付。"),
        new DetailEntryPoint("QUARANTINE", "待检处理", "8.2.2.2", false, null,
            "待检数量的分桶已落库，但待检单据的处置流程属 8.2.2.2，尚未交付。"),
        new DetailEntryPoint("PICK_OUTBOUND", "拣货与出库", "8.3", false, null,
            "8.3.1.1 出库单 / 8.3.2.1 拣货任务 / 8.3.3.1 复核 / 8.3.4.1 发货交接，尚未交付。"),
        new DetailEntryPoint("ASSEMBLY", "组装作业", "8.4", false, null,
            "8.4.1.1 组装单 / 8.4.2.1 拆解单，尚未交付。"),
        new DetailEntryPoint("PICKING_POLICY", "仓库行为参数", "8.1.1.3", false, null,
            "批次序列号开关、拣货策略、默认复核比例、是否允许拆零发货、工作时段与班次属仓库配置，尚未交付。"),
        new DetailEntryPoint("LABOR_EFFICIENCY", "作业效率", "8.5.1.1", true, null,
            "人效报表与作业排名已交付，可在仓库分析里看本仓的人效。"));
  }

  // ================================================================ 维护

  /**
   * 改仓库基础信息与地址。
   *
   * <p>应用层校验给出可读错误，DB 层仍有 CHECK 与唯一索引兜底 —— 两层都要在，各挡各的。
   *
   * <p><b>ARCHIVED 是终态</b>：归档后不能再改任何字段，也不能取消归档。
   * 这与 8.1.1.1 文件头「ARCHIVED 是终态，本模块没有回退动作（属 8.1.1.2）」是一致的：
   * 「回退动作」指的是取消归档这件事本身不存在，而不是「归档后还能改地址」。
   */
  public WarehouseUpdated update(UUID warehouseId, WarehouseUpdateInput input) {
    var current =
        sql.fetchOne(
            "select code,status from warehouse"
                + " where tenant_id = current_setting('app.current_tenant', true)::uuid and id=?",
            warehouseId);
    if (current == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "仓库不存在");

    String status = current.get("status", String.class);
    if ("ARCHIVED".equals(status))
      throw new IllegalArgumentException("仓库已归档，不能再修改：归档是终态，没有回退动作");

    String name = input.name().trim();
    if (name.isEmpty()) throw new IllegalArgumentException("仓库名称不能为空");
    String country = normalizeCountry(input.countryCode());
    String timezone = requireIanaTimezone(input.timezone());

    String target =
        input.status() == null || input.status().isBlank()
            ? status
            : input.status().trim().toUpperCase(Locale.ROOT);
    if (!STATUSES.contains(target))
      throw new IllegalArgumentException("不支持的仓库状态：" + input.status());
    if ("ARCHIVED".equals(target) && !"ACTIVE".equals(status) && !"INACTIVE".equals(status))
      throw new IllegalArgumentException("不支持的状态迁移：" + status + " → " + target);

    var r =
        sql.fetchOne(
            """
            update warehouse
               set name=?, country_code=?, timezone=?,
                   address_line1=?, address_line2=?, city=?, region=?, postal_code=?,
                   status=?, updated_at=now()
             where tenant_id = current_setting('app.current_tenant', true)::uuid and id=?
            returning code, name, status, updated_at
            """,
            name, country, timezone,
            blankToNull(input.addressLine1()), blankToNull(input.addressLine2()),
            blankToNull(input.city()), blankToNull(input.region()), blankToNull(input.postalCode()),
            target, warehouseId);
    return new WarehouseUpdated(
        warehouseId,
        r.get("code", String.class),
        r.get("name", String.class),
        r.get("status", String.class),
        r.get("updated_at", java.time.OffsetDateTime.class),
        "归档是终态：归档后本仓不再接受任何修改。编码、仓库类型与货权法人不可改 —— "
            + "它们分别是仓库标识、记账口径与财务前提，改动会让已落库数据失去指向。");
  }

  // ================================================================ 辅助

  private void requireWarehouse(UUID warehouseId) {
    boolean exists =
        sql.fetchOne(
                "select 1 from warehouse"
                    + " where tenant_id = current_setting('app.current_tenant', true)::uuid and id=?",
                warehouseId)
            != null;
    if (!exists) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "仓库不存在");
  }

  private static Object[] bindsToArray(List<Object> binds, Object... tail) {
    Object[] all = new Object[binds.size() + tail.length];
    for (int i = 0; i < binds.size(); i++) all[i] = binds.get(i);
    for (int i = 0; i < tail.length; i++) all[binds.size() + i] = tail[i];
    return all;
  }

  private static boolean isBlank(String v) {
    return v == null || v.isBlank();
  }

  /** 地址字段的空白串归一成 null：「填了几个空格」与「没填」在界面上无法区分。 */
  private static String blankToNull(String v) {
    return isBlank(v) ? null : v.trim();
  }

  /** 国家地区统一大写；格式必须是两位字母（V1 的 CHECK 只管长度，字母由这里兜住）。 */
  private static String normalizeCountry(String raw) {
    String code = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
    if (!code.matches("[A-Z]{2}")) {
      throw new IllegalArgumentException("国家地区必须是 ISO-3166-1 的两位字母代码：" + raw);
    }
    return code;
  }

  /**
   * 时区必须是 IANA ID，**偏移量在这里被显式拒绝**。
   *
   * <p>为什么不能只靠 {@code ZoneId.of}：它其实**接受** ISO-8601 偏移量
   * （{@code ZoneId.of("+08:00")} 返回一个 {@code ZoneOffset}，不抛异常）。
   * 于是「+08:00」能一路存进库，而这个值不表达夏令时规则 ——
   * 纽约什么时候切夏令时答不出来，仓库的当地墙上时间也就永远只能按固定偏移算。
   * 前端 createProblem 已经拦了偏移量，但那只是体验；后端必须自己再拦一次，
   * 否则绕过界面直接调端点就能写进一个坏时区。
   *
   * <p>顺带把「无法识别的时区」翻译成 {@link IllegalArgumentException}：
   * {@code ZoneId.of} 抛的 {@code DateTimeException} 既不是
   * {@code IllegalArgumentException} 也不是 {@code DateTimeParseException}，
   * 直接冒到 ApiErrorHandler 之外会变成 500 —— 一个用户输错时区得到的服务端错误。
   */
  private static String requireIanaTimezone(String raw) {
    String zone = raw == null ? "" : raw.trim();
    if (zone.isEmpty()) throw new IllegalArgumentException("时区不能为空");
    if (zone.matches("[+-]\\d{1,2}(:\\d{2})?")) {
      throw new IllegalArgumentException(
          "时区要填 IANA ID（如 Asia/Shanghai），偏移量不表达夏令时规则：" + zone);
    }
    try {
      ZoneId.of(zone);
    } catch (DateTimeException e) {
      throw new IllegalArgumentException("无法识别的时区：" + zone);
    }
    return zone;
  }

  private static String statusLabel(String status) {
    return switch (status) {
      case "ACTIVE" -> "启用中";
      case "INACTIVE" -> "已停用";
      case "ARCHIVED" -> "已归档";
      default -> status;
    };
  }

  private static String locationTypeLabel(String type) {
    return switch (type) {
      case "STORAGE" -> "存储库位";
      case "RECEIVING" -> "收货库位";
      case "SHIPPING" -> "发货库位";
      case "QUARANTINE" -> "待检库位";
      case "DEFECTIVE" -> "残次库位";
      case "FROZEN" -> "冻结库位";
      default -> type;
    };
  }

  private static String workTypeLabel(String type) {
    return switch (type) {
      case "PICKING" -> "拣货";
      case "PACKING" -> "打包";
      case "REVIEW" -> "复核";
      default -> type;
    };
  }
}