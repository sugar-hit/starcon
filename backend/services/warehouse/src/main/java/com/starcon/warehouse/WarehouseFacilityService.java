package com.starcon.warehouse;

import static com.starcon.warehouse.WarehouseFacilityModels.*;

import com.starcon.tenancy.Ids;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 8.1.1.1 仓库列表。
 *
 * <p>蓝图：「展示租户下所有仓库：类型（自营/三方/海外仓/平台仓/监管仓）、所属法人、国家地区、
 * 时区、启用状态、库存量与作业量，支持筛选与新增。」
 *
 * <p>**纯聚合视图，不存任何独立数据** —— 与 7.1.1.1 库存总览同一条纪律。
 * 合计、每页行、能力声明全部从 {@code warehouse} 与事实表上重算，
 * 没有「列表专用汇总表」，所以「合计 == 逐行求和」不是靠约定成立而是结构上无从违反。
 *
 * <p><b>两个数不相加</b>：自有账本（{@code inventory_balance}）与平台仓观测值
 * （{@code platform_stock_observation}）在响应里是**两个字段**。V55 用两条触发器在 DB 层
 * 挡住「平台仓记自有账本」和「自有仓记观测值」，这里在类型层再钉一次。
 */
@Service
public class WarehouseFacilityService {

  /** 单页最大行数：仓库列表是主数据，不会像库存明细那样上万行，但仍然要有上限。 */
  static final int MAX_PAGE_SIZE = 200;

  /** 排序键白名单：排序键来自 query string，只允许这五个。 */
  static final List<String> SORTS =
      List.of("CODE", "NAME", "KIND", "STOCK", "WORK");

  /** 状态词表与 V1 的 CHECK 逐字一致。ARCHIVED 是终态，本模块没有回退动作（属 8.1.1.2）。 */
  private static final List<String> STATUSES = List.of("ACTIVE", "INACTIVE", "ARCHIVED");

  /** 墙面上时间的固定格式；时区本身单独给一个字段，不靠这个串反推。 */
  private static final DateTimeFormatter LOCAL_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

  private final DSLContext sql;
  private final Clock clock;

  public WarehouseFacilityService(DSLContext sql) {
    this.sql = sql;
    // 当地时间依赖「现在」：用的是系统时钟。仓库当地时间在测试里断言格式与时区一致性，
    // 而不是与某个固定时刻比较 —— 固定时钟会让测试在跨时区时变成一个只测假想的用例。
    this.clock = Clock.systemDefaultZone();
  }

  // ================================================================ 列表

  /** 仓库列表。筛选（类型 / 法人 / 国家 / 状态）+ 关键字 + 排序 + 真分页。 */
  public WarehouseFacilityPage list(FacilityQuery q) {
    int size = Math.min(MAX_PAGE_SIZE, Math.max(1, q.size() == null ? 20 : q.size()));
    int page = Math.max(0, q.page() == null ? 0 : q.page());

    var where = new ArrayList<String>();
    var binds = new ArrayList<Object>();
    filters(q, where, binds);
    String sqlWhere = where.isEmpty() ? "" : " where " + String.join(" and ", where);

    long total = sql.fetchOne("select count(*) from warehouse w" + sqlWhere, binds.toArray()).get(0, Long.class);

    List<WarehouseRow> rows =
        sql.fetch(
                selectRows() + sqlWhere + orderBy(q.sort()) + " limit ? offset ?",
                bindsToArray(binds, size, page * size))
            .map(r -> row(r));

    // detailUnavailable 从 true 翻成 false：8.1.1.2 仓库详情已交付
    // （GET /warehouse/facilities/{id}）。本行的这个布尔值就是两条车道之间约定的交接开关 ——
    // 8.1.1.1 交付时它写死 true 让前端禁用详情入口，现在由交付详情的那条车道来翻。
    return new WarehouseFacilityPage(summary(), rows, total, page, size, false, summaryNote());
  }

  /**
   * 行查询。
   *
   * <p>四个聚合各自来自**一个**标量子查询，而不是 join 出四张事实表再 group by ——
   * 后者在库位与批次维度展开后会变成笛卡尔积，四个 count 与三个 sum 会互相放大。
   * 这里每个子查询自己先按 warehouse_id 收口，再被引用一次。
   *
   * <p>租户谓词出现两次是刻意的：RLS 策略（{@code app.current_tenant}）是第一道防线，
   * 显式 {@code tenant_id=} 是第二道。忘了绑定上下文时第二道让查询当场返回空而不是全表。
   */
  private String selectRows() {
    return """
        select w.id, w.code, w.name, w.kind, w.country_code, w.timezone, w.status, w.is_default,
               w.default_legal_entity_id, w.created_at, w.updated_at,
               le.code as le_code, le.name as le_name,
               %s as legal_entity_count,
               %s as location_count,
               %s as sku_count,
               %s as on_hand,
               %s as reserved,
               %s as available,
               %s as balance_rows,
               %s as movement_count
        from warehouse w
        left join legal_entity_ref le on le.tenant_id=w.tenant_id and le.id=w.default_legal_entity_id
        """
        .formatted(
            stockSubquery("count(distinct b.legal_entity_id)", """
                from inventory_balance b
                 where b.tenant_id=w.tenant_id and b.warehouse_id=w.id"""),
            stockSubquery("count(distinct l.id)", """
                from warehouse_location l
                 where l.tenant_id=w.tenant_id and l.warehouse_id=w.id"""),
            stockSubquery("count(distinct b.sku_id)", """
                from inventory_balance b
                 where b.tenant_id=w.tenant_id and b.warehouse_id=w.id"""),
            stockSubquery("coalesce(sum(b.on_hand),0)", """
                from inventory_balance b
                 where b.tenant_id=w.tenant_id and b.warehouse_id=w.id"""),
            stockSubquery("coalesce(sum(b.reserved),0)", """
                from inventory_balance b
                 where b.tenant_id=w.tenant_id and b.warehouse_id=w.id"""),
            // available 只能来自生成列，没有第三个来源。
            stockSubquery("coalesce(sum(b.available),0)", """
                from inventory_balance b
                 where b.tenant_id=w.tenant_id and b.warehouse_id=w.id"""),
            stockSubquery("count(*)", """
                from inventory_balance b
                 where b.tenant_id=w.tenant_id and b.warehouse_id=w.id"""),
            // 库存变动笔数：entry → balance → warehouse。作业量里唯一「逐笔」的维度。
            """
            (select count(*) from inventory_entry e
               join inventory_balance b on b.tenant_id=e.tenant_id and b.id=e.balance_id
              where e.tenant_id=w.tenant_id and b.warehouse_id=w.id)
            """);
  }

  private static String stockSubquery(String aggregate, String from) {
    return "(select %s %s)" .formatted(aggregate, from.stripLeading());
  }

  private static void filters(FacilityQuery q, List<String> where, List<Object> binds) {
    where.add("w.tenant_id = current_setting('app.current_tenant', true)::uuid");
    if (q.kind() != null && !q.kind().isBlank()) {
      where.add("w.kind = ?");
      binds.add(kind(q.kind()).name());
    }
    if (q.legalEntityId() != null) {
      where.add("w.default_legal_entity_id = ?");
      binds.add(q.legalEntityId());
    }
    if (q.countryCode() != null && !q.countryCode().isBlank()) {
      where.add("w.country_code = ?");
      binds.add(normalizeCountry(q.countryCode()));
    }
    if (q.status() != null && !q.status().isBlank()) {
      String status = q.status().toUpperCase(Locale.ROOT).trim();
      if (!STATUSES.contains(status)) throw new IllegalArgumentException("不支持的状态：" + q.status());
      where.add("w.status = ?");
      binds.add(status);
    }
    if (q.keyword() != null && !q.keyword().isBlank()) {
      where.add("(w.code ilike ? or w.name ilike ?)");
      String like = "%" + q.keyword().trim() + "%";
      binds.add(like);
      binds.add(like);
    }
  }

  private static Object[] bindsToArray(List<Object> binds, Object... tail) {
    Object[] all = new Object[binds.size() + tail.length];
    for (int i = 0; i < binds.size(); i++) all[i] = binds.get(i);
    for (int i = 0; i < tail.length; i++) all[binds.size() + i] = tail[i];
    return all;
  }

  private String orderBy(String sort) {
    String key = sort == null || sort.isBlank() ? "CODE" : sort.toUpperCase(Locale.ROOT);
    if (!SORTS.contains(key)) throw new IllegalArgumentException("不支持的排序键：" + sort);
    // 一律以 code 收尾：排序键相同时必须有确定次序，否则同一页两次请求可能给出不同顺序。
    return switch (key) {
      case "NAME" -> " order by w.name, w.code";
      case "KIND" -> " order by w.kind, w.code";
      case "STOCK" -> " order by (%s) desc nulls last, w.code".formatted(availableExpr());
      case "WORK" -> " order by (%s) desc nulls last, w.code".formatted(workTotalExpr());
      default -> " order by w.code";
    };
  }

  private static String availableExpr() {
    return "(select coalesce(sum(b.available),0) from inventory_balance b"
        + " where b.tenant_id=w.tenant_id and b.warehouse_id=w.id)";
  }

  /**
   * 作业单合计表达式：盘点任务 + 调入 + 调出。
   *
   * <p>三个子查询各自按仓库收口后相加，**不** join 起来 —— join 会让一张调拨单同时
   * 命中 from 与 to 两行而翻倍（from_warehouse_id 与 to_warehouse_id 在同一张表上）。
   */
  private static String workTotalExpr() {
    return "(" + stocktakeTasks("count(*)") + "+" + transferCount("to") + "+" + transferCount("from") + ")";
  }

  /** 盘点任务：按仓发起的盘点单 + 按该仓库位发起的盘点单，两种范围都算这个仓的作业。 */
  private static String stocktakeTasks(String aggregate) {
    return """
        (select %s from stocktake_task t
           join stocktake_sheet sh on sh.tenant_id=t.tenant_id and sh.id=t.sheet_id
          where t.tenant_id=w.tenant_id
            and ((sh.scope_type='WAREHOUSE' and sh.scope_key=w.id)
              or (sh.scope_type='LOCATION' and exists (
                   select 1 from warehouse_location l
                    where l.tenant_id=sh.tenant_id and l.id=sh.scope_key
                      and l.warehouse_id=w.id))))
        """
        .formatted(aggregate);
  }

  private static String transferCount(String side) {
    String column = side.equals("to") ? "to_warehouse_id" : "from_warehouse_id";
    return "(select count(*) from transfer_order o"
        + " where o.tenant_id=w.tenant_id and o." + column + "=w.id)";
  }

  // ================================================================ 合计

  /**
   * 全量合计。**不受分页与筛选影响** —— 它回答「我名下总共有多少货、多少作业」，
   * 拿筛选后的数当合计会让「切了个筛选条件，怎么总量变了」看起来像数据丢了。
   */
  private FacilitySummary summary() {
    var counts =
        sql.fetchOne(
                """
                select count(*) as warehouse_count,
                       count(*) filter (where status='ACTIVE') as active_count,
                       count(*) filter (where kind='PLATFORM_FBA') as platform_count
                  from warehouse
                 where tenant_id = current_setting('app.current_tenant', true)::uuid
                """);

    var stock =
        sql.fetchOne(
                """
                select coalesce(sum(on_hand),0) as on_hand,
                       coalesce(sum(reserved),0) as reserved,
                       coalesce(sum(available),0) as available
                  from inventory_balance
                 where tenant_id = current_setting('app.current_tenant', true)::uuid
                """);

    BigDecimal observed =
        sql.fetchOne(
                """
                select coalesce(sum(o.observed_on_hand),0)
                  from platform_stock_observation o
                 where o.tenant_id = current_setting('app.current_tenant', true)::uuid
                   and o.id in (select distinct on (warehouse_id,sku_id,source) id
                                  from platform_stock_observation
                                 where tenant_id = current_setting('app.current_tenant', true)::uuid
                                 order by warehouse_id,sku_id,source,collected_at desc)
                """)
            .get(0, BigDecimal.class);

    long[] work = workTotals();

    return new FacilitySummary(
        counts.get("warehouse_count", Long.class),
        counts.get("active_count", Long.class),
        counts.get("platform_count", Long.class),
        stock.get("on_hand", BigDecimal.class),
        stock.get("reserved", BigDecimal.class),
        stock.get("available", BigDecimal.class),
        observed,
        work[0],
        work[1]);
  }

  /** 全租户的作业单合计与未结数，与列表口径逐条同源。 */
  private long[] workTotals() {
    var row =
        sql.fetchOne(
                """
                select (select count(*) from stocktake_task t
                          where t.tenant_id = current_setting('app.current_tenant', true)::uuid)
                     + (select count(*) from transfer_order o
                          where o.tenant_id = current_setting('app.current_tenant', true)::uuid) as work_total,
                       (select count(*) from stocktake_task t
                          join stocktake_sheet sh on sh.tenant_id=t.tenant_id and sh.id=t.sheet_id
                         where t.tenant_id = current_setting('app.current_tenant', true)::uuid
                           and sh.status <> 'COUNTED')
                     + (select count(*) from transfer_order o
                          where o.tenant_id = current_setting('app.current_tenant', true)::uuid
                            and o.status <> 'RECEIVED') as open_total
                """);
    return new long[] {row.get("work_total", Long.class), row.get("open_total", Long.class)};
  }

  private String summaryNote() {
    return "合计不受分页与筛选影响；平台仓数量来自观测值，与自有账本不相加。";
  }

  // ================================================================ 行映射

  private WarehouseRow row(org.jooq.Record r) {
    UUID id = r.get("id", UUID.class);
    WarehouseKind kind = WarehouseKind.valueOf(r.get("kind", String.class));
    StockMetrics stock =
        new StockMetrics(
            r.get("on_hand", BigDecimal.class),
            r.get("reserved", BigDecimal.class),
            r.get("available", BigDecimal.class),
            r.get("balance_rows", Long.class));
    return new WarehouseRow(
        id,
        r.get("code", String.class),
        r.get("name", String.class),
        kind,
        kind.label(),
        r.get("country_code", String.class),
        r.get("timezone", String.class),
        localTime(r.get("timezone", String.class)),
        r.get("status", String.class),
        Boolean.TRUE.equals(r.get("is_default", Boolean.class)),
        r.get("default_legal_entity_id", UUID.class),
        r.get("le_code", String.class),
        r.get("le_name", String.class),
        r.get("legal_entity_count", Long.class),
        r.get("location_count", Long.class),
        r.get("sku_count", Long.class),
        stock,
        platformStock(id, kind),
        work(id),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  /**
   * 平台仓观测值。每个 {@code (仓库, SKU, 来源)} 只取最新一条 ——
   * platform_stock_observation 是 append-only 的历史序列（V55），求和会把历史观测叠加进去。
   */
  private PlatformStock platformStock(UUID warehouseId, WarehouseKind kind) {
    if (!kind.platformObserved()) return null;   // 自有仓恒为 null：不是「0」，是「这个来源不存在」
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
    return new PlatformStock(
        r.get("source_count", Long.class),
        r.get("observed_on_hand", BigDecimal.class),
        r.get("observed_available", BigDecimal.class),
        r.get("observed_inbound", BigDecimal.class),
        r.get("latest_collected_at", OffsetDateTime.class));
  }

  /** 单个仓库的作业量，口径与 {@link #workTotalExpr()}、{@link #workTotals()} 逐条一致。 */
  private WorkVolume work(UUID warehouseId) {
    var r =
        sql.fetchOne(
            """
            select (select count(*) from stocktake_task t
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
            warehouseId, warehouseId, warehouseId, warehouseId, warehouseId);

    long taskCount = r.get("task_count", Long.class);
    long transferIn = r.get("transfer_in", Long.class);
    long transferOut = r.get("transfer_out", Long.class);
    long open =
        r.get("task_open", Long.class)
            + r.get("transfer_in_open", Long.class)
            + r.get("transfer_out_open", Long.class);
    return new WorkVolume(
        taskCount,
        transferIn,
        transferOut,
        r.get("movement_count", Long.class),
        taskCount + transferIn + transferOut,
        open);
  }

  /**
   * 仓库当地墙上时间。
   *
   * <p>时区**不能**只给一个裸字符串让前端猜：偏移量不表达夏令时规则，
   * 「+08:00」无法回答「纽约这周几号该切夏令时」。所以行上给 IANA ID + 算好的当地时刻两个字段。
   *
   * <p>库里存了一个 JDK 不认识的时区串时**如实报出来**（返回 null + 说明），
   * 而不是静默退化成 UTC —— 退化成 UTC 会让运营看到一个看起来正常但错了 8 小时的时刻。
   */
  private String localTime(String timezone) {
    try {
      ZoneId zone = ZoneId.of(timezone);
      return LocalDateTime.ofInstant(clock.instant(), zone).format(LOCAL_TIME);
    } catch (DateTimeException e) {
      return null;
    }
  }

  // ================================================================ 能力与下拉项

  /**
   * 作业量维度接入情况。
   *
   * <p>蓝图 8.1.1.1 要「作业量」，而 8.2 收货 / 8.3 出库 / 8.4 组装三条作业链尚未交付。
   * 这里逐条报出哪些已接入、哪些没接入、归哪个模块 —— 前端据此禁用不可用的维度，
   * 而不是显示一个永远为 0 的数字让用户以为「这个仓不作业」。
   */
  public List<WorkVolumeCapability> workCapabilities() {
    return List.of(
        new WorkVolumeCapability(
            "STOCKTAKE", "盘点任务", true, "7.4.2.1", "已接入 stocktake_sheet / stocktake_task"),
        new WorkVolumeCapability(
            "TRANSFER", "仓间调拨", true, "7.5.1.1", "已接入 transfer_order，调入与调出分别计数"),
        new WorkVolumeCapability(
            "INVENTORY_MOVEMENT", "库存变动", true, "7.1.2.1", "已接入 inventory_entry 的带符号分录条数"),
        new WorkVolumeCapability(
            "RECEIVING", "收货作业", false, "8.2",
            "8.2.1.1 收货单 / 8.2.2.1 上架任务尚未交付，goods_receipt 表不存在，作业量里没有这一项"),
        new WorkVolumeCapability(
            "PICKING", "拣货与出库", false, "8.3",
            "8.3.1.1 出库单 / 8.3.2.1 拣货任务 / 8.3.3.1 复核 / 8.3.4.1 发货交接尚未交付，作业量里没有这一项"),
        new WorkVolumeCapability(
            "ASSEMBLY", "组装作业", false, "8.4",
            "8.4.1.1 组装单 / 8.4.2.1 拆解单尚未交付，作业量里没有这一项"));
  }

  /** 筛选与录入下拉项。 */
  public FacilityOptions options() {
    var countries =
        sql.fetch(
                "select distinct country_code from warehouse"
                    + " where tenant_id = current_setting('app.current_tenant', true)::uuid"
                    + " order by country_code")
            .map(r -> new CountryOption(r.get("country_code", String.class)));

    var entities =
        sql.fetch(
                "select id,code,name,status from legal_entity_ref"
                    + " where tenant_id = current_setting('app.current_tenant', true)::uuid"
                    + " order by code")
            .map(r -> new LegalEntityOption(
                r.get("id", UUID.class),
                r.get("code", String.class),
                r.get("name", String.class),
                r.get("status", String.class)));

    return new FacilityOptions(
        Arrays.stream(WarehouseKind.values())
            .map(k -> new KindOption(k.name(), k.label(), k.platformObserved()))
            .toList(),
        countries,
        entities,
        List.of(
            new WarehouseFacilityStatusOption("ACTIVE", "启用中"),
            new WarehouseFacilityStatusOption("INACTIVE", "已停用"),
            new WarehouseFacilityStatusOption("ARCHIVED", "已归档")),
        "时区填 IANA ID（如 Asia/Shanghai / America/Los_Angeles），由服务端按它算当地时间",
        "国家地区是 ISO-3166-1 alpha-2 两位码；下拉只列本租户真实在用的国家，"
            + "系统不内置国别表以免与法务口径漂移");
  }

  // ================================================================ 新增

  /**
   * 新增仓库。
   *
   * <p>校验在应用层做（给出可读的错误），DB 层仍有 CHECK 与唯一索引兜底 ——
   * 「应用层校验 + DB 约束」两层都要在，各挡各的：前者挡用户，后者和别的车道一样挡 SQL。
   */
  public WarehouseCreated create(WarehouseCreateInput input) {
    String code = input.code().trim().toUpperCase(Locale.ROOT);
    String name = input.name().trim();
    WarehouseKind kind = kind(input.kind());
    String country = normalizeCountry(input.countryCode());
    String timezone = input.timezone().trim();
    ZoneId.of(timezone);   // 抛 DateTimeException 即拒绝：错时区会让当地时间永远算错
    UUID legalEntityId = input.defaultLegalEntityId();
    String status = input.status() == null || input.status().isBlank()
        ? "ACTIVE"
        : input.status().toUpperCase(Locale.ROOT).trim();
    if (!STATUSES.contains(status)) throw new IllegalArgumentException("不支持的状态：" + input.status());

    // 所属法人必须存在且启用：货权绑到一个停用法人上会让「这批货算谁的」无法回答。
    var entity =
        sql.fetchOne(
            "select status from legal_entity_ref"
                + " where tenant_id = current_setting('app.current_tenant', true)::uuid and id=?",
            legalEntityId);
    if (entity == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "法人不存在");
    if (!"ACTIVE".equals(entity.get("status", String.class)))
      throw new IllegalArgumentException("所选法人已停用，不能作为仓库的货权法人");

    if (Boolean.TRUE.equals(input.isDefault())) {
      long current = sql.fetchOne(
              "select count(*) from warehouse where tenant_id = current_setting('app.current_tenant', true)::uuid"
                  + " and is_default and status='ACTIVE'")
          .get(0, Long.class);
      if (current > 0) throw new IllegalArgumentException("该租户已有启用中的默认发货仓，请先取消原默认仓");
    }

    UUID id = Ids.next();
    try {
      sql.execute(
          "insert into warehouse(id,tenant_id,code,name,kind,country_code,timezone,"
              + "default_legal_entity_id,is_default,status)"
              + " values(?,current_setting('app.current_tenant', true)::uuid,?,?,?,?,?,?,?,?)",
          id, code, name, kind.name(), country, timezone, legalEntityId,
          Boolean.TRUE.equals(input.isDefault()), status);
    } catch (org.springframework.dao.DuplicateKeyException e) {
      // Spring Boot 的 DefaultExceptionTranslatorExecuteListener 会把 jOOQ 的异常翻成
      // org.springframework.dao.* ，所以这里接 Spring 的类型而不是 org.jooq 的
      // —— 接错类型的表现是「catch 永远不执行，用户看到一句带 SQL 的英文」。
      // DB 的 SQLSTATE 不该直接抛给界面，所以翻译成人话。
      throw new IllegalArgumentException("仓库编码 " + code + " 在本租户已存在");
    }
    return new WarehouseCreated(id, code, kind);
  }

  // ================================================================ 校验辅助

  static WarehouseKind kind(String raw) {
    try {
      return WarehouseKind.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("不支持的仓库类型：" + raw);
    }
  }

  /** 国家地区统一大写；格式必须是两位字母（V1 的 CHECK 只管长度，字母由这里兜住）。 */
  static String normalizeCountry(String raw) {
    String code = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
    if (!code.matches("[A-Z]{2}")) {
      throw new IllegalArgumentException("国家地区必须是 ISO-3166-1 的两位字母代码：" + raw);
    }
    return code;
  }
}