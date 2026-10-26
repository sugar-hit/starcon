package com.starcon.warehouse;

import static com.starcon.warehouse.InventoryRiskProjectionModels.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Service;

/**
 * 1.1.3.1 库存风险的**只读投影**（卷 01 工作台与门户）。
 *
 * <p><b>本类只读不写，且不产生任何新的业务事实。</b>卷 01 的定位写死了：「不产生新的业务事实，只聚合各系统的投影」
 * 与「工作台只读 + 跳转；一切状态变更回到归属系统完成」。所以这里没有迁移、没有新表、没有快照 ——
 * 派生数据存下来只会比它描述的库存更早过期，而过期的那个数恰好是防超卖用的数（与 7.1.1.3 同一纪律）。
 *
 * <p><b>七条实现纪律</b>：
 *
 * <ol>
 *   <li><b>可用量是唯一的数量口径</b>：{@code available = Σ stock_bucket.available}（生成列
 *       {@code on_hand − reserved}），本端点只做 (仓 × SKU) 汇总，<b>不重算、不重定义</b>。
 *       蓝图输出句里的「可用量」就是它。</li>
 *   <li><b>阈值随行返回并标注档位</b>：{@code inventory_policy} 的解析顺序是 SKU > WAREHOUSE > DEFAULT
 *       （V1 文件头写死）。命中哪一档必须报出来 —— 否则界面上「配成 60」与「没人配」分不开，
 *       而这两个在缺货判定里后果完全不同。未配置一律是 {@code null}，<b>绝不写 0</b>。</li>
 *   <li><b>呆滞线来自 7.6.1.1</b>：{@code inventory_aging_policy.obsolete_days}，未配置时回落
 *       {@link InventoryAgingService#DEFAULT_OBSOLETE_DAYS} —— 那是既有模块的兜底默认值，
 *       本端点不新造一个数。</li>
 *   <li><b>库龄口径与 7.6.1.1 逐字一致</b>：{@code coalesce(inventory_lot.received_on, stock_bucket.created_at::date)}，
 *       <b>刻意不用 {@code last_movement_at}</b>（每天出 10 件的滞销品会被算成库龄 0 天）。</li>
 *   <li><b>超卖判定用分配明细的未了结量</b>：{@code reservation_allocation.remaining}（生成列）在
 *       {@code status ∈ (HELD, COMMITTED)} 时的和，按 (仓 × SKU) 汇总 —— 分配明细才有仓库维度，
 *       预占需求本身没有。</li>
 *   <li><b>在途口径与 7.5.1.2 逐字一致</b>：在途量 = 仍在途的腿的 {@code inventory_transfer.quantity} 之和
 *       （<b>不是</b> {@code quantity − settled_quantity}：既有清单展示的就是 {@code quantity}，
 *       两处口径分叉会让「在途」这一列对不上）。</li>
 *   <li><b>租户只由 RLS 与服务层谓词决定</b>：SQL 里显式带
 *       {@code tenant_id = current_setting('app.current_tenant', true)::uuid}，
 *       客户端的 {@code X-Tenant-Id} 头进不来也改不动它。</li>
 * </ol>
 *
 * <p><b>本服务不碰的东西</b>：销售速度（日均销）属 trade 服务的 {@code stockout_sales_velocity}，
 * 采购到货跟踪属 trade 的 {@code purchase_arrival_tracking}（无 SKU 维度，只能到采购单粒度）。
 * 两者都不在本服务读，跨服务聚合由卷 01 的前端聚合层完成。
 */
@Service
public class InventoryRiskProjectionService {

  /** 单页最大行数。 */
  static final int MAX_PAGE_SIZE = 500;

  /**
   * 主聚合：按 (仓 × SKU) 汇总 bucket 事实。
   *
   * <p>质量状态、库位、批次都是<b>本服务</b>的表，可以放心 JOIN（与 7.1.1.2 / 7.1.1.3 同一套口径）。
   */
  private static final String BASE_CTE =
      """
      with buckets as (
        select b.tenant_id,
               b.warehouse_id,
               b.sku_id,
               sum(b.on_hand)   as on_hand,
               sum(b.reserved)  as reserved,
               sum(b.available) as available,
               sum(case when q.role='SELLABLE' and loc.pickable and loc.status='ACTIVE'
                         and (l.id is null or l.status='ACTIVE')
                        then b.available else 0 end) as sellable_before_buffer,
               count(*)         as bucket_count,
               count(distinct b.location_id) as location_count,
               count(distinct b.lot_id)      as lot_count,
               min(coalesce(l.received_on, b.created_at::date)) as inbound_date,
               (current_date - min(coalesce(l.received_on, b.created_at::date))) as aging_days,
               max(b.last_movement_at) as last_movement_at
          from stock_bucket b
          join inventory_quality_status q
            on q.tenant_id=b.tenant_id and q.id=b.quality_status_id
          join warehouse_location loc
            on loc.tenant_id=b.tenant_id and loc.warehouse_id=b.warehouse_id and loc.id=b.location_id
          left join inventory_lot l
            on l.tenant_id=b.tenant_id and l.id=b.lot_id
         where b.tenant_id = current_setting('app.current_tenant', true)::uuid
         group by b.tenant_id, b.warehouse_id, b.sku_id
      )
      """;

  private final DSLContext sql;

  public InventoryRiskProjectionService(DSLContext sql) {
    this.sql = sql;
  }

  // ================================================================ 能力声明

  /**
   * 本模块的能力与上下游可用性。
   *
   * <p>四个输入里三个在本服务（可用量 / 预占 / 在途），两个不在（销售速度在 trade、采购到货在 trade）。
   * 哪一个接通了必须由接口自己报出，而不是让调用方从空数组里猜。
   */
  public RiskProjectionCapabilities capabilities() {
    return new RiskProjectionCapabilities(
        true,
        "7.1.1.2 / V2 stock_bucket + 7.1.1.3 / V1",
        "可用量 = Σ stock_bucket.available（生成列 on_hand − reserved），按 (仓 × SKU) 汇总；"
            + "本端点只汇总，不重算、不重定义 —— 可售量（sellable）是 7.1.1.3 的另一个口径，本模块不重造。",
        true,
        "7.2.2 / V1 inventory_policy",
        "库存阈值取 inventory_policy 的 min_on_hand / max_on_hand / reorder_point / safety_stock，"
            + "解析顺序 SKU > WAREHOUSE > DEFAULT（V1 文件头写死），命中档位随行返回。"
            + "未配置一律是 null（不是 0）：「没人配」与「配成 0」在缺货判定里后果完全不同。",
        true,
        "7.6.1.1 / V65 inventory_aging_policy",
        "呆滞线取 inventory_aging_policy.obsolete_days（逐 SKU 唯一），未配置时回落 7.6.1.1 的兜底默认值 "
            + InventoryAgingService.DEFAULT_OBSOLETE_DAYS + " 天；库龄口径与 7.6.1.1 逐字一致。",
        true,
        "7.2.1.2 / V107 reservation_allocation",
        "活跃预占 = 分配明细的 remaining（生成列）在 status ∈ (HELD, COMMITTED) 时的和，按 (仓 × SKU) 汇总 —— "
            + "分配明细才有仓库维度，预占需求本身没有。",
        true,
        "7.5.1.2 / V1+V63 inventory_transfer",
        "在途量 = 仍在途的腿的 quantity 之和（与 /warehouse/inventory/in-transit 逐字一致，"
            + "刻意不是 quantity − settled_quantity）。头程（FIRST_MILE）、采购（PURCHASE）、调拨（TRANSFER）分列。",
        false,
        "6.2.1.1 / trade",
        "日均销登记在 trade 的 stockout_sales_velocity（V234），属另一个服务的事实表，"
            + "本服务不跨库读（AGENTS.md 模块边界纪律）。卷 01 的聚合层在前端读取 "
            + "/trade/stockout-risk/velocities 后合并。",
        false,
        "6.3.2.2 / trade",
        "采购到货跟踪表 purchase_arrival_tracking 以采购单为粒度、**没有 SKU 维度**，"
            + "无法支撑逐 SKU 的风险清单。逐 SKU 的采购在途口径改用本服务 inventory_transfer 的 "
            + "PURCHASE 腿（同样在途、同样有 SKU）。",
        "四类风险的判定阈值全部来自上表，**没有任何硬编码常量**："
            + "缺货 = 可用量 < min_on_hand；超卖 = 活跃预占未了结量 > 可用量（结构性判定，无阈值）；"
            + "呆滞 = 库龄 ≥ obsolete_days；超储 = 在手量 > max_on_hand。"
            + "未配置阈值的行不参与该类判定，并在界面上如实标注「未配置阈值」。",
        "可用天数 = 可用量 ÷ 日均销量，除法在精确十进制上做、向下取整为整数天；"
            + "分母为 0（登记事实：本 SKU 无消耗）或未登记销售速度时，答案是「不可判定」（null），"
            + "既不是 0 也不是无穷大。",
        "本端点**只读**：没有写入口、没有快照、不落库。工作台的一切状态变更都回到归属系统完成。");
  }

  // ================================================================ 投影

  /** (仓 × SKU) 的库存事实投影。真分页 + 真筛选。 */
  public RiskProjectionPage projection(ProjectionQuery q) {
    int size = Math.min(MAX_PAGE_SIZE, Math.max(1, q.size() == null ? 100 : q.size()));
    int page = Math.max(0, q.page() == null ? 0 : q.page());

    var where = new ArrayList<String>();
    var binds = new ArrayList<Object>();
    if (q.warehouseId() != null) {
      where.add("bk.warehouse_id = ?::uuid");
      binds.add(q.warehouseId());
    }
    if (q.skuId() != null) {
      where.add("bk.sku_id = ?::uuid");
      binds.add(q.skuId());
    }
    if (q.warehouseKind() != null && !q.warehouseKind().isBlank()) {
      where.add("w.kind = ?");
      binds.add(q.warehouseKind().trim().toUpperCase(java.util.Locale.ROOT));
    }
    if (q.keyword() != null && !q.keyword().isBlank()) {
      where.add("(s.code ilike ? or s.name ilike ?)");
      String k = "%" + q.keyword().trim() + "%";
      binds.add(k);
      binds.add(k);
    }
    String clause = where.isEmpty() ? "" : " where " + String.join(" and ", where);

    // ---- 数据库时钟：评估基准日与计算时刻一律取 DB，不取 Java 时钟
    var clock =
        sql.fetchOne(
            "select (current_timestamp at time zone 'UTC')::date::text as d, clock_timestamp() as ts");
    String asOfDate = clock.get("d", String.class);
    OffsetDateTime computedAt = clock.get("ts", OffsetDateTime.class);

    long total =
        sql.fetchOne(
                BASE_CTE
                    + "select count(*) from buckets bk"
                    + " join warehouse w on w.tenant_id=bk.tenant_id and w.id=bk.warehouse_id"
                    + " join sku_ref s on s.tenant_id=bk.tenant_id and s.id=bk.sku_id"
                    + clause,
                binds.toArray())
            .get(0, Long.class);

    var rows =
        sql.fetch(
            BASE_CTE
                + "select w.id as warehouse_id,w.code as warehouse_code,w.name as warehouse_name,"
                + " w.kind as warehouse_kind,bk.sku_id,s.code as sku_code,s.name as sku_name,"
                + " bk.on_hand,bk.reserved,bk.available,bk.sellable_before_buffer,"
                + " bk.bucket_count,bk.location_count,bk.lot_count,"
                + " bk.inbound_date,bk.aging_days,bk.last_movement_at"
                + " from buckets bk"
                + " join warehouse w on w.tenant_id=bk.tenant_id and w.id=bk.warehouse_id"
                + " join sku_ref s on s.tenant_id=bk.tenant_id and s.id=bk.sku_id"
                + clause
                + " order by w.code, s.code"
                + " limit ? offset ?",
            append(binds, size, (long) page * size));

    // ---- 阈值（Java 侧按档位解析，档位随行返回）
    Policies policies = policies();
    Map<UUID, Integer> obsoleteDays = obsoleteDays();
    Map<String, Reserved> reserved = activeReservations();
    Map<String, InTransit> transit = inTransit();

    List<RiskProjectionRow> items = new ArrayList<>();
    for (Record r : rows) {
      UUID warehouseId = r.get("warehouse_id", UUID.class);
      UUID skuId = r.get("sku_id", UUID.class);
      String key = key(warehouseId, skuId);
      Policy policy = policies.resolve(warehouseId, skuId);
      Reserved res = reserved.get(key);
      InTransit leg = transit.get(key);
      Integer aging = r.get("aging_days", Integer.class);
      LocalDate inbound = r.get("inbound_date", LocalDate.class);
      Integer configured = obsoleteDays.get(skuId);

      items.add(
          new RiskProjectionRow(
              warehouseId,
              r.get("warehouse_code", String.class),
              r.get("warehouse_name", String.class),
              r.get("warehouse_kind", String.class),
              kindLabel(r.get("warehouse_kind", String.class)),
              skuId,
              r.get("sku_code", String.class),
              r.get("sku_name", String.class),
              decimal(r, "on_hand"),
              decimal(r, "reserved"),
              decimal(r, "available"),
              decimal(r, "sellable_before_buffer"),
              count(r, "bucket_count"),
              count(r, "location_count"),
              count(r, "lot_count"),
              inbound,
              aging,
              inbound == null ? "NO_INBOUND_DATE" : "LOT_RECEIVED_ON_OR_BUCKET_CREATED_AT",
              r.get("last_movement_at", OffsetDateTime.class),
              policy.minOnHand(),
              policy.maxOnHand(),
              policy.reorderPoint(),
              policy.safetyStock(),
              policy.scopeType(),
              policy.scopeKey(),
              configured == null ? InventoryAgingService.DEFAULT_OBSOLETE_DAYS : configured,
              configured == null ? "DEFAULT" : "POLICY",
              res == null ? BigDecimal.ZERO : res.quantity(),
              res == null ? 0L : res.count(),
              leg == null ? BigDecimal.ZERO : leg.total(),
              leg == null ? BigDecimal.ZERO : leg.firstMile(),
              leg == null ? BigDecimal.ZERO : leg.purchase(),
              leg == null ? BigDecimal.ZERO : leg.transfer(),
              leg == null ? 0L : leg.count()));
    }

    return new RiskProjectionPage(
        items,
        total,
        page,
        size,
        asOfDate,
        computedAt,
        warehouseKindOptions(),
        warehouseOptions(),
        "合计取全量（不受分页影响）；本页只列出前 " + size + " 条，翻页可继续。");
  }

  // ================================================================ 阈值解析

  /** 一条解析后的阈值：每个字段各自取最具体的一档。 */
  private record Policy(
      BigDecimal minOnHand,
      BigDecimal maxOnHand,
      BigDecimal reorderPoint,
      BigDecimal safetyStock,
      String scopeType,
      String scopeKey) {}

  /** 三个档位的候选策略行。 */
  private static final class Policies {
    private final Map<String, Record> bySku = new LinkedHashMap<>();
    private final Map<String, Record> byWarehouse = new LinkedHashMap<>();
    private Record fallback;

    Policy resolve(UUID warehouseId, UUID skuId) {
      Record sku = bySku.get(skuId.toString());
      Record wh = byWarehouse.get(warehouseId.toString());
      Record def = fallback;
      BigDecimal min = pick(sku, wh, def, "min_on_hand");
      BigDecimal max = pick(sku, wh, def, "max_on_hand");
      BigDecimal reorder = pick(sku, wh, def, "reorder_point");
      BigDecimal safety = pick(sku, wh, def, "safety_stock");
      // 档位标注取「四个字段里**实际供上值**的最具体一档」——
      // SKU 档配了 min_on_hand 但没配 max_on_hand 时，超储线要回落到 WAREHOUSE 档，
      // 档位标注也必须如实说 WAREHOUSE 供了超储线，而不是笼统报一个「SKU 档」。
      Record scopeSource = null;
      for (String field : new String[] {"min_on_hand", "max_on_hand", "reorder_point", "safety_stock"}) {
        Record source = sourceOf(sku, wh, def, field);
        if (source == null) {
          continue;
        }
        if (scopeSource == null || scopeRank(source) < scopeRank(scopeSource)) {
          scopeSource = source;
        }
      }
      String scopeType = scopeSource == null ? "NONE" : scopeSource.get("scope_type", String.class);
      String scopeKey = scopeSource == null ? "" : scopeSource.get("scope_key", String.class);
      return new Policy(min, max, reorder, safety, scopeType, scopeKey);
    }
  }

  /**
   * 解析库存阈值。
   *
   * <p>解析顺序 <b>SKU > WAREHOUSE > DEFAULT</b>（V1 文件头写死）。每个字段<b>各自</b>取最具体的一档：
   * SKU 档配了 {@code min_on_hand} 但没配 {@code max_on_hand} 时，超储线应当回落到 WAREHOUSE 档，
   * 而不是整个策略行都算「命中 SKU 档」而让超储线变成 null。
   */
  private Policies policies() {
    var rows =
        sql.fetch(
            "select scope_type,scope_key,min_on_hand,max_on_hand,reorder_point,safety_stock"
                + " from inventory_policy"
                + " where tenant_id = current_setting('app.current_tenant', true)::uuid"
                + " and enabled and scope_type in ('SKU','WAREHOUSE','DEFAULT')");
    Policies policies = new Policies();
    for (Record r : rows) {
      String scopeType = r.get("scope_type", String.class);
      String scopeKey = r.get("scope_key", String.class);
      switch (scopeType) {
        case "SKU" -> policies.bySku.put(scopeKey, r);
        case "WAREHOUSE" -> policies.byWarehouse.put(scopeKey, r);
        case "DEFAULT" -> policies.fallback = r;
        default -> {
          // CATEGORY 档在本服务无从解析（stock_bucket 没有类目维度），刻意不参与。
        }
      }
    }
    return policies;
  }

  /** 逐字段取最具体的一档：SKU > WAREHOUSE > DEFAULT。 */
  private static BigDecimal pick(Record sku, Record wh, Record def, String field) {
    Record source = sourceOf(sku, wh, def, field);
    return source == null ? null : source.get(field, BigDecimal.class);
  }

  /** 某个字段的值出自哪一条策略行（SKU > WAREHOUSE > DEFAULT）。 */
  private static Record sourceOf(Record sku, Record wh, Record def, String field) {
    for (Record candidate : new Record[] {sku, wh, def}) {
      if (candidate == null) {
        continue;
      }
      if (candidate.get(field, BigDecimal.class) != null) {
        return candidate;
      }
    }
    return null;
  }

  /** 档位具体度：越小越具体。 */
  private static int scopeRank(Record record) {
    return switch (record.get("scope_type", String.class)) {
      case "SKU" -> 0;
      case "WAREHOUSE" -> 1;
      default -> 2;
    };
  }

  // ================================================================ 呆滞阈值

  /** 逐 SKU 的呆滞天数。未配置的 SKU 不在表里，由调用方回落 7.6.1.1 的兜底默认值。 */
  private Map<UUID, Integer> obsoleteDays() {
    var rows =
        sql.fetch(
            "select sku_id,obsolete_days from inventory_aging_policy"
                + " where tenant_id = current_setting('app.current_tenant', true)::uuid and enabled");
    Map<UUID, Integer> result = new LinkedHashMap<>();
    for (Record r : rows) {
      result.put(r.get("sku_id", UUID.class), r.get("obsolete_days", Integer.class));
    }
    return result;
  }

  // ================================================================ 活跃预占

  private record Reserved(BigDecimal quantity, long count) {}

  /** 按 (仓 × SKU) 汇总的活跃预占未了结量。 */
  private Map<String, Reserved> activeReservations() {
    var rows =
        sql.fetch(
            "select a.warehouse_id,r.sku_id,coalesce(sum(a.remaining),0) as qty,count(*) as cnt"
                + " from reservation_allocation a"
                + " join inventory_reservation r"
                + "   on r.tenant_id=a.tenant_id and r.id=a.reservation_id"
                + " where a.tenant_id = current_setting('app.current_tenant', true)::uuid"
                + " and a.status in ('HELD','COMMITTED')"
                + " group by a.warehouse_id,r.sku_id");
    Map<String, Reserved> result = new LinkedHashMap<>();
    for (Record r : rows) {
      result.put(
          key(r.get("warehouse_id", UUID.class), r.get("sku_id", UUID.class)),
          new Reserved(decimal(r, "qty"), count(r, "cnt")));
    }
    return result;
  }

  // ================================================================ 在途

  private record InTransit(
      BigDecimal total, BigDecimal firstMile, BigDecimal purchase, BigDecimal transfer, long count) {}

  /**
   * 按 (仓 × SKU) 汇总的仍在途量。
   *
   * <p>口径与 7.5.1.2 的在途清单<b>逐字一致</b>：在途量 = 仍在途的腿的 {@code quantity} 之和。
   * 终点仓为空的腿（FBA 发往平台仓）不属于任何一个仓，不进本表 —— 那批货不在我方任何仓里。
   */
  private Map<String, InTransit> inTransit() {
    var rows =
        sql.fetch(
            "select to_warehouse_id,sku_id,"
                + " coalesce(sum(quantity),0) as total,"
                + " coalesce(sum(case when transit_kind='FIRST_MILE' then quantity else 0 end),0) as first_mile,"
                + " coalesce(sum(case when transit_kind='PURCHASE' then quantity else 0 end),0) as purchase,"
                + " coalesce(sum(case when transit_kind='TRANSFER' then quantity else 0 end),0) as transfer,"
                + " count(*) as cnt"
                + " from inventory_transfer"
                + " where tenant_id = current_setting('app.current_tenant', true)::uuid"
                + " and status='IN_TRANSIT' and to_warehouse_id is not null"
                + " group by to_warehouse_id,sku_id");
    Map<String, InTransit> result = new LinkedHashMap<>();
    for (Record r : rows) {
      result.put(
          key(r.get("to_warehouse_id", UUID.class), r.get("sku_id", UUID.class)),
          new InTransit(
              decimal(r, "total"),
              decimal(r, "first_mile"),
              decimal(r, "purchase"),
              decimal(r, "transfer"),
              count(r, "cnt")));
    }
    return result;
  }

  // ================================================================ 下拉项

  /** 仓库类型下拉。 */
  private List<WarehouseKindOption> warehouseKindOptions() {
    var rows =
        sql.fetch(
            "select kind,count(*) as cnt from warehouse"
                + " where tenant_id = current_setting('app.current_tenant', true)::uuid"
                + " and status='ACTIVE' group by kind order by kind");
    List<WarehouseKindOption> options = new ArrayList<>();
    for (Record r : rows) {
      String kind = r.get("kind", String.class);
      options.add(new WarehouseKindOption(kind, kindLabel(kind), count(r, "cnt")));
    }
    return options;
  }

  /** 仓库下拉。 */
  private List<WarehouseOption> warehouseOptions() {
    var rows =
        sql.fetch(
            "select id,code,name,kind from warehouse"
                + " where tenant_id = current_setting('app.current_tenant', true)::uuid"
                + " and status='ACTIVE' order by code");
    List<WarehouseOption> options = new ArrayList<>();
    for (Record r : rows) {
      String kind = r.get("kind", String.class);
      options.add(
          new WarehouseOption(
              r.get("id", UUID.class),
              r.get("code", String.class),
              r.get("name", String.class),
              kind,
              kindLabel(kind)));
    }
    return options;
  }

  // ================================================================ 小工具

  private static String key(UUID warehouseId, UUID skuId) {
    return warehouseId + "|" + skuId;
  }

  /** 仓库类型的中文标签（受控词表见 warehouse_kind_check）。 */
  static String kindLabel(String kind) {
    if (kind == null) {
      return "未知";
    }
    return switch (kind) {
      case "LOCAL" -> "本地仓";
      case "OVERSEAS" -> "海外仓";
      case "PLATFORM_FBA" -> "平台 FBA 仓";
      case "THIRD_PARTY" -> "第三方仓";
      case "BONDED" -> "保税仓";
      default -> kind;
    };
  }

  private static BigDecimal decimal(Record r, String field) {
    BigDecimal value = r.get(field, BigDecimal.class);
    return value == null ? BigDecimal.ZERO : value;
  }

  private static long count(Record r, String field) {
    Long value = r.get(field, Long.class);
    return value == null ? 0L : value;
  }

  private static Object[] append(List<Object> binds, Object... extra) {
    List<Object> all = new ArrayList<>(binds);
    all.addAll(List.of(extra));
    return all.toArray();
  }
}
