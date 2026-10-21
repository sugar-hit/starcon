package com.starcon.warehouse;

import static com.starcon.warehouse.InventoryBucketModels.*;
import static com.starcon.warehouse.WarehouseModels.QualityRole;

import com.starcon.tenancy.Ids;
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
import org.springframework.stereotype.Service;

/**
 * 7.1.1.2 库存明细：bucket 级的最细粒度视图。
 *
 * <p>蓝图原话：「bucket 是库存的最小事实单位，界面上必须能看到『在哪个货位、什么批次、 什么质量状态』，否则无法解释差异。」所以一行 = 法人货权 × SKU × 仓库 × 库位 ×
 * 批次 × 质量状态。
 *
 * <p><b>与 7.1.1.1 的分层关系</b>（本模块最重要的设计约束）：
 *
 * <ul>
 *   <li>{@code inventory_balance} 是**仓库级**的可用量决策单位，键不含库位， 这一点由 V1 迁移文件头写死，不在本模块改动。
 *   <li>{@code stock_bucket} 是**库位 × 批次**粒度的事实表（V2 迁移），是本模块的读对象。
 *   <li>两者的关系由 V2 的复合外键 + 延迟约束触发器在 DB 层强制： Σ bucket == balance，行归属的仓/SKU/法人/质量状态逐列等于它的 balance 行。
 * </ul>
 *
 * <p>因此本服务**不写**任何库存数量 —— 它是纯读视图。所有写入口属于 7.1.2.1 流水、 7.4.1.1 调整、7.5.1.1 调拨（尚未交付）。写方的并发基础（{@code
 * version} 乐观锁、 {@code last_movement_at} 业务时间）已在 V2 建好，本模块读出来供下游对齐。
 *
 * <p>汇总口径全部来自迁移文件头，Java 侧只做求和，不二次推导： available 只由生成列 {@code on_hand - reserved} 聚合而来。
 */
@Service
public class InventoryBucketService {

  /** 单页最大行数：bucket 行数远多于 balance 行，一次拉太多会把 JVM 拖垮。 */
  static final int MAX_PAGE_SIZE = 200;

  /** 排序键白名单：排序键来自 query string，只允许这五个。 */
  private static final List<String> SORTS =
      List.of("ON_HAND", "AVAILABLE", "SKU_CODE", "LOCATION_CODE", "LAST_MOVEMENT");

  /** 明细页的固定 FROM。全部是本服务自己的表（sku / 法人仍是逻辑 ID + 引用登记）。 */
  private static final String FROM =
      clause(
          """
          from stock_bucket b
          join sku_ref s on s.tenant_id=b.tenant_id and s.id=b.sku_id
          join warehouse w on w.tenant_id=b.tenant_id and w.id=b.warehouse_id
          join warehouse_location loc
            on loc.tenant_id=b.tenant_id and loc.warehouse_id=b.warehouse_id and loc.id=b.location_id
          left join inventory_lot lot on lot.tenant_id=b.tenant_id and lot.id=b.lot_id
          join legal_entity_ref le on le.tenant_id=b.tenant_id and le.id=b.legal_entity_id
          join inventory_quality_status q on q.tenant_id=b.tenant_id and q.id=b.quality_status_id
          """);

  /** 文本块片段的前导空格兜底（与 InventoryOverviewService 同一条理由：Java 文本块会丢掉首行换行）。 */
  private static String clause(String fragment) {
    return " " + fragment.stripLeading();
  }

  private final DSLContext sql;

  public InventoryBucketService(DSLContext sql) {
    this.sql = sql;
  }

  // ------------------------------------------------------------ 行内操作入口

  /**
   * 本模块提供的行内操作入口。
   *
   * <p>蓝图要求明细行带「发起调整 / 发起调拨 / 查看流水」三个入口。调拨落在尚未交付的 7.5.1.1，所以这里的做法是：
   *
   * <ul>
   *   <li>href 照给 —— 它是带 bucket 定位参数的**路由契约**，不是死链；
   *   <li>{@code available=false} + note 说明为什么现在点不了（目标模块未交付）。
   * </ul>
   *
   * <p>「查看流水」（7.1.2.1）与「发起调整」（7.4.1.1）已交付，因此 available=true：那三个入口里
   * 现在有两个点得动，再标成"未交付"就是在页面上说一个假状态。
   *
   * <p>这么做的理由与 7.1.1.1 拒绝建占位页一致：给一个必然 404 的按钮，比不给更糟 —— 用户会以为系统坏了。但完全不给入口又违背蓝图对「行内操作入口」的要求，
   * 所以契约给全、可用性如实标注。目标模块交付时只要改这里的 available。
   */
  public List<BucketAction> actions() {
    return List.of(
        action("ADJUST", "发起调整", "/inventory/adjustments", "7.4.1.1", true, ""),
        action("TRANSFER", "发起调拨", "/inventory/transfer", "7.5.1.1", false, ""),
        action("LEDGER", "查看流水", "/inventory/ledger", "7.1.2.1", true, ""));
  }

  private static BucketAction action(
      String code, String label, String route, String owner, boolean available, String note) {
    return new BucketAction(
        code,
        label,
        // 契约里带上六个定位维度：目标模块落地后，入口能直接定位到这一行 bucket
        // 而不是回到一个"从哪开始"的列表页。台账读的是 balance 口径，
        // locationId / lotId 这两个 bucket 级维度它接不住，所以带上也不影响查询（后端忽略未知参数）。
        route + "?skuId={skuId}&warehouseId={warehouseId}&locationId={locationId}&lotId={lotId}",
        available,
        owner,
        available ? owner + " 已交付，入口可用" : "目标模块 " + owner + " 尚未交付，入口契约已就位但暂不可用");
  }

  // ------------------------------------------------------------ 明细查询

  /** 明细查询。纯读：不写任何表。 */
  public InventoryBucketDetail detail(BucketDetailQuery query) {
    int size = Math.min(MAX_PAGE_SIZE, Math.max(1, query.size() == null ? 50 : query.size()));
    int page = Math.max(0, query.page() == null ? 0 : query.page());
    var where = new ArrayList<String>();
    var binds = new ArrayList<Object>();
    conditions(query, Scope.BUCKET, where, binds);
    String sqlWhere = where.isEmpty() ? "" : " where " + String.join(" and ", where);

    long total =
        sql.fetchOne("select count(*)" + FROM + sqlWhere, binds.toArray()).get(0, Long.class);

    var rows =
        sql.fetch(
            "select b.id,b.balance_id,"
                + "b.sku_id,s.code as sku_code,s.name as sku_name,"
                + "b.warehouse_id,w.code as warehouse_code,w.name as warehouse_name,"
                + "b.location_id,loc.code as location_code,loc.name as location_name,"
                + "loc.type as location_type,loc.pickable,"
                + "b.lot_id,lot.lot_code,lot.expires_on,lot.status as lot_status,"
                + "b.legal_entity_id,le.code as legal_entity_code,le.name as legal_entity_name,"
                + "b.quality_status_id,q.code as quality_status_code,q.name as quality_status_name,q.role,"
                + "b.on_hand,b.reserved,b.available,"
                + "b.available<=0 as zero_stock,"
                + "b.version,b.last_movement_at,b.updated_at"
                + FROM
                + sqlWhere
                + order(query)
                + " limit ? offset ?",
            append(binds, size, (long) page * size).toArray());

    return new InventoryBucketDetail(
        summary(sqlWhere, binds),
        rows.stream().map(this::row).toList(),
        reconciliation(query),
        actions(),
        total,
        page,
        size);
  }

  /** bucket 合计。合计来自**同一条 FROM** 再聚合一次，而不是把当前页在 Java 里加起来： 后者算的是"这一页的和"，翻页时合计会变，核对线也就无从复核。 */
  private BucketSummary summary(String sqlWhere, List<Object> binds) {
    Record r =
        sql.fetchOne(
            "select coalesce(sum(b.on_hand),0) as on_hand,"
                + " coalesce(sum(b.reserved),0) as reserved,"
                + " coalesce(sum(b.available),0) as available,"
                + " coalesce(sum(case when q.role='SELLABLE' then b.on_hand else 0 end),0) as sellable,"
                + " coalesce(sum(case when q.role='PENDING_INSPECTION' then b.on_hand else 0 end),0) as pending,"
                + " coalesce(sum(case when q.role='DEFECTIVE' then b.on_hand else 0 end),0) as defective,"
                + " count(*) as row_count,"
                + " count(distinct b.location_id) as location_count,"
                + " count(distinct b.lot_id) as lot_count,"
                + " count(*) filter (where b.lot_id is null) as unbatched_rows,"
                + " max(b.last_movement_at) as last_movement"
                + FROM
                + sqlWhere,
            binds.toArray());
    return new BucketSummary(
        decimal(r, "on_hand"),
        decimal(r, "reserved"),
        decimal(r, "available"),
        decimal(r, "sellable"),
        decimal(r, "pending"),
        decimal(r, "defective"),
        r.get("row_count", Long.class),
        r.get("location_count", Long.class),
        r.get("lot_count", Long.class),
        r.get("unbatched_rows", Long.class),
        r.get("last_movement", OffsetDateTime.class));
  }

  /**
   * bucket ↔ balance 核对。
   *
   * <p>三个数字必须分清楚，否则界面上会把"筛选后的子集"误显示成"不平"：
   *
   * <ul>
   *   <li><b>命中范围</b> = 当前筛选命中到的 bucket 所归属的 balance 行（{@code touchedBalanceCount}）。
   *   <li><b>待核对数</b> = 这些 balance 行**全部** bucket 的汇总，**不受行筛选影响**。 所以按单个库位筛选时，对比的两边都是同一个 balance
   *       的全量，不会假报差异。
   *   <li><b>未分解</b> = balance 级筛选范围内、但一条 bucket 都没有的 balance 行。 这些行是合法状态（还没拆到库位），必须与"差异"分开显示。
   * </ul>
   */
  private BucketReconciliation reconciliation(BucketDetailQuery query) {
    var touchedWhere = new ArrayList<String>();
    var touchedBinds = new ArrayList<Object>();
    conditions(query, Scope.BUCKET, touchedWhere, touchedBinds);
    // touched 只取 balance 维度（distinct），行级筛选不影响"这条 balance 被碰到没有"。
    // tenant_id / balance_id 必须带表别名：FROM 里六张表都有 tenant_id，不限定就是 ambiguous。
    String touched =
        "select distinct b.tenant_id,b.balance_id"
            + FROM
            + (touchedWhere.isEmpty() ? "" : " where " + String.join(" and ", touchedWhere));

    Record r =
        sql.fetchOne(
            "with touched as ("
                + touched
                + "), touched_sum as ("
                + " select coalesce(sum(bal.on_hand),0) as on_hand,"
                + " coalesce(sum(bal.reserved),0) as reserved,"
                + " count(*) as balance_count"
                + " from inventory_balance bal join touched t"
                + " on t.tenant_id=bal.tenant_id and t.balance_id=bal.id"
                + "), bucket_sum as ("
                + " select coalesce(sum(k.on_hand),0) as on_hand,"
                + " coalesce(sum(k.reserved),0) as reserved,"
                + " count(*) as bucket_count"
                + " from stock_bucket k join touched t"
                + " on t.tenant_id=k.tenant_id and t.balance_id=k.balance_id"
                + ") select ts.on_hand as balance_on_hand,ts.reserved as balance_reserved,"
                + " bs.on_hand as bucket_on_hand,bs.reserved as bucket_reserved,"
                + " ts.balance_count as balance_count, bs.bucket_count as bucket_count"
                + " from touched_sum ts cross join bucket_sum bs",
            touchedBinds.toArray());

    var balanceOnHand = decimal(r, "balance_on_hand");
    var balanceReserved = decimal(r, "balance_reserved");
    var bucketOnHand = decimal(r, "bucket_on_hand");
    var bucketReserved = decimal(r, "bucket_reserved");

    return new BucketReconciliation(
        balanceOnHand,
        balanceReserved,
        bucketOnHand,
        bucketReserved,
        r.get("balance_count", Long.class),
        r.get("bucket_count", Long.class),
        balanceOnHand.compareTo(bucketOnHand) == 0,
        balanceReserved.compareTo(bucketReserved) == 0,
        undecomposed(query));
  }

  /**
   * 符合 balance 级筛选、但一条 bucket 都没有的 balance 行数。
   *
   * <p>用 balance 级条件（不含库位 / 批次 / 可拣），否则"筛了一个空库位"会被算成 一整片未分解 —— 那是筛选条件造成的，不是账有问题。
   */
  private long undecomposed(BucketDetailQuery query) {
    var where = new ArrayList<String>();
    var binds = new ArrayList<Object>();
    // 「没有 bucket」必须与筛选条件 AND 在**同一个** where 里。写成两段 where 会拼出
    // "... where not exists (...) where b.warehouse_id = ?"，是语法错误而不是过滤。
    where.add(
        "not exists (select 1 from stock_bucket k"
            + " where k.tenant_id=b.tenant_id and k.balance_id=b.id)");
    conditions(query, Scope.BALANCE, where, binds);
    // FROM 的别名必须与 conditions() 生成的 "b.xxx" 一致：balance 级条件的列前缀是 b。
    var r =
        sql.fetchOne(
            "select count(*) from inventory_balance b"
                + " join sku_ref s on s.tenant_id=b.tenant_id and s.id=b.sku_id"
                + " join warehouse w on w.tenant_id=b.tenant_id and w.id=b.warehouse_id"
                + " join legal_entity_ref le on le.tenant_id=b.tenant_id and le.id=b.legal_entity_id"
                + " join inventory_quality_status q"
                + " on q.tenant_id=b.tenant_id and q.id=b.quality_status_id"
                + " where "
                + String.join(" and ", where),
            binds.toArray());
    return r == null ? 0 : r.get(0, Long.class);
  }

  // ------------------------------------------------------------ 条件与排序

  /** 条件作用域：bucket 级（含库位/批次/可拣）还是 balance 级（只有四个粗维度）。 */
  private enum Scope {
    BUCKET,
    BALANCE
  }

  /** 拼 WHERE。所有值都走绑定，标识符只用常量白名单。 */
  private void conditions(
      BucketDetailQuery query, Scope scope, List<String> where, List<Object> binds) {
    if (query.warehouseId() != null) {
      where.add("b.warehouse_id = ?");
      binds.add(query.warehouseId());
    }
    if (query.legalEntityId() != null) {
      where.add("b.legal_entity_id = ?");
      binds.add(query.legalEntityId());
    }
    if (query.skuId() != null) {
      where.add("b.sku_id = ?");
      binds.add(query.skuId());
    }
    if (query.qualityStatusId() != null) {
      where.add("b.quality_status_id = ?");
      binds.add(query.qualityStatusId());
    }
    if (query.keyword() != null && !query.keyword().isBlank()) {
      // bucket 作用域下关键字额外命中库位与批次：明细页最常见的用法就是
      // 「找某个货架上的这批货」。balance 作用域没有 loc / lot 两张表，
      // 那两个条件留在 BUCKET 分支里加，否则会生成引用不存在别名的 SQL。
      where.add(
          "(s.code ilike ? or s.name ilike ? or w.code ilike ? or w.name ilike ?"
              + (scope == Scope.BUCKET
                  ? " or loc.code ilike ? or loc.name ilike ? or lot.lot_code ilike ?"
                  : "")
              + " or le.code ilike ? or le.name ilike ? or q.name ilike ?)");
      String k = "%" + query.keyword().trim() + "%";
      int hits = scope == Scope.BUCKET ? 10 : 7;
      for (int i = 0; i < hits; i++) binds.add(k);
    }
    // 库位 / 批次 / 可拣三个条件只属于 bucket 作用域：balance 表没有这些维度，
    // 把它们塞进 balance 级条件会生成一条永远不成立的 SQL。
    if (scope == Scope.BUCKET) {
      if (query.locationId() != null) {
        where.add("b.location_id = ?");
        binds.add(query.locationId());
      }
      if (query.lotId() != null) {
        where.add("b.lot_id = ?");
        binds.add(query.lotId());
      }
      if (Boolean.TRUE.equals(query.onlyPickable())) {
        where.add("loc.pickable");
      }
    }
  }

  private String order(BucketDetailQuery query) {
    String sort = query.sort() == null ? "ON_HAND" : query.sort().toUpperCase(Locale.ROOT);
    if (!SORTS.contains(sort)) {
      throw new IllegalArgumentException("不支持的排序键：" + query.sort());
    }
    // 决胜键恒定追加 b.id：同数量时翻页不会错位或重复。
    return switch (sort) {
      case "AVAILABLE" -> " order by b.available desc,b.id";
      case "SKU_CODE" -> " order by s.code,b.id";
      case "LOCATION_CODE" -> " order by loc.code,b.id";
      case "LAST_MOVEMENT" -> " order by b.last_movement_at desc,b.id";
      default -> " order by b.on_hand desc,b.id";
    };
  }

  // ------------------------------------------------------------ 行组装

  private BucketRow row(Record r) {
    var expiresOn = r.get("expires_on", LocalDate.class);
    return new BucketRow(
        r.get("id", UUID.class),
        r.get("balance_id", UUID.class),
        r.get("sku_id", UUID.class),
        r.get("sku_code", String.class),
        r.get("sku_name", String.class),
        r.get("warehouse_id", UUID.class),
        r.get("warehouse_code", String.class),
        r.get("warehouse_name", String.class),
        r.get("location_id", UUID.class),
        r.get("location_code", String.class),
        r.get("location_name", String.class),
        r.get("location_type", String.class),
        Boolean.TRUE.equals(r.get("pickable", Boolean.class)),
        r.get("lot_id", UUID.class),
        r.get("lot_code", String.class),
        expiresOn,
        r.get("lot_status", String.class),
        // 效期判定只做展示：分配策略（FEFO/FIFO）属 7.3.1.2，本模块不越界做决策。
        expiresOn != null && expiresOn.isBefore(LocalDate.now()),
        r.get("legal_entity_id", UUID.class),
        r.get("legal_entity_code", String.class),
        r.get("legal_entity_name", String.class),
        r.get("quality_status_id", UUID.class),
        r.get("quality_status_code", String.class),
        r.get("quality_status_name", String.class),
        QualityRole.valueOf(r.get("role", String.class)),
        decimal(r, "on_hand"),
        decimal(r, "reserved"),
        decimal(r, "available"),
        Boolean.TRUE.equals(r.get("zero_stock", Boolean.class)),
        r.get("version", Long.class),
        r.get("last_movement_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  // ------------------------------------------------------------ 下拉参照数据

  /** 库位下拉。库位档案属本服务；层级与自动分配属 08.1.2.x，本模块只读。 */
  public List<LocationRef> locations(UUID warehouseId, String type) {
    var where = new ArrayList<String>();
    var binds = new ArrayList<Object>();
    where.add("loc.status='ACTIVE'");
    if (warehouseId != null) {
      where.add("loc.warehouse_id=?");
      binds.add(warehouseId);
    }
    if (type != null && !type.isBlank()) {
      where.add("loc.type=?");
      binds.add(type.toUpperCase(Locale.ROOT));
    }
    return sql.fetch(
            "select loc.id,loc.warehouse_id,w.code as warehouse_code,loc.code,loc.name,loc.type,"
                + "loc.pickable,loc.status from warehouse_location loc"
                + " join warehouse w on w.tenant_id=loc.tenant_id and w.id=loc.warehouse_id"
                + " where "
                + String.join(" and ", where)
                + " order by w.code,loc.type,loc.code",
            binds.toArray())
        .map(
            r ->
                new LocationRef(
                    r.get("id", UUID.class),
                    r.get("warehouse_id", UUID.class),
                    r.get("warehouse_code", String.class),
                    r.get("code", String.class),
                    r.get("name", String.class),
                    r.get("type", String.class),
                    Boolean.TRUE.equals(r.get("pickable", Boolean.class)),
                    r.get("status", String.class)));
  }

  /** 批次下拉。 */
  public List<LotRef> lots(UUID skuId, String keyword) {
    var where = new ArrayList<String>();
    var binds = new ArrayList<Object>();
    if (skuId != null) {
      where.add("l.sku_id=?");
      binds.add(skuId);
    }
    if (keyword != null && !keyword.isBlank()) {
      where.add("l.lot_code ilike ?");
      binds.add("%" + keyword.trim() + "%");
    }
    return sql.fetch(
            "select l.id,l.sku_id,s.code as sku_code,l.lot_code,l.expires_on,l.status"
                + " from inventory_lot l"
                + " join sku_ref s on s.tenant_id=l.tenant_id and s.id=l.sku_id"
                + (where.isEmpty() ? "" : " where " + String.join(" and ", where))
                + " order by s.code,l.lot_code",
            binds.toArray())
        .map(
            r ->
                new LotRef(
                    r.get("id", UUID.class),
                    r.get("sku_id", UUID.class),
                    r.get("sku_code", String.class),
                    r.get("lot_code", String.class),
                    r.get("expires_on", LocalDate.class),
                    r.get("status", String.class)));
  }

  /**
   * 建一条 bucket（仅供库存业务写入方与集成测试夹具使用）。
   *
   * <p>蓝图把 bucket 的写入口划给 7.1.2.1 流水 / 7.4.1.1 调整 / 7.5.1.1 调拨， 那三个模块尚未交付，所以本模块**不暴露任何 HTTP 写端点**。
   * 这里的方法是同服务内的最小写入原语：balance 与 bucket 在同一个事务里改， 由 V2 的延迟约束触发器在提交前核对守恒。
   *
   * <p>{@code lastMovementAt} 必须由调用方给出（库存业务的发生时间，**业务时间**）： DB 触发器会拒绝"改了数量但没给新时间"的写入，所以这里不接受 null。
   */
  public UUID recordBucket(
      UUID balanceId,
      UUID locationId,
      UUID lotId,
      BigDecimal onHand,
      OffsetDateTime lastMovementAt) {
    if (lastMovementAt == null) {
      throw new IllegalArgumentException("必须提供 lastMovementAt：它是库存业务的发生时间");
    }
    var existing =
        sql.fetchOne(
            "select b.id,b.on_hand,b.reserved,b.balance_id from stock_bucket b"
                + " where b.balance_id=? and b.location_id=? and b.lot_id is not distinct from ?",
            balanceId,
            locationId,
            lotId);
    if (existing == null) {
      // timestamptz 必须显式 ?::timestamptz：jOOQ 把 OffsetDateTime 绑到 timestamptz
      // 时不带类型信息，靠推断会出现静默的时区偏移。
      sql.execute(
          "insert into stock_bucket(id,tenant_id,legal_entity_id,sku_id,warehouse_id,location_id,"
              + "lot_id,quality_status_id,balance_id,on_hand,reserved,last_movement_at)"
              + " select ?,bal.tenant_id,bal.legal_entity_id,bal.sku_id,bal.warehouse_id,?,?,"
              + "bal.quality_status_id,bal.id,?,0,?::timestamptz"
              + " from inventory_balance bal where bal.id=?",
          Ids.next(),
          locationId,
          lotId,
          onHand,
          lastMovementAt,
          balanceId);
    } else {
      sql.execute(
          "update stock_bucket set on_hand=?,last_movement_at=?::timestamptz where id=?",
          onHand,
          lastMovementAt,
          existing.get("id", UUID.class));
    }
    return sql.fetchOne(
            "select id from stock_bucket where balance_id=? and location_id=?"
                + " and lot_id is not distinct from ?",
            balanceId,
            locationId,
            lotId)
        .get(0, UUID.class);
  }

  private static BigDecimal decimal(Record r, String field) {
    var v = r.get(field, BigDecimal.class);
    return v == null ? BigDecimal.ZERO : v;
  }

  private List<Object> append(List<Object> binds, Object... more) {
    var all = new ArrayList<>(binds);
    all.addAll(Arrays.asList(more));
    return all;
  }
}
