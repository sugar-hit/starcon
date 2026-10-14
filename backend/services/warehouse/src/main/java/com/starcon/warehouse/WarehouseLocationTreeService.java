package com.starcon.warehouse;

import static com.starcon.warehouse.WarehouseLocationTreeModels.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 8.1.2.1 库位树。
 *
 * <p>蓝图：「维护库位层级与属性（区 → 巷 → 排 → 架 → 层 → 格），支持容量、承重、类型
 * （存储/暂存/待检/残次/冻结）、是否可拣。输入：库位增删改与层级调整；防环校验。
 * 输出：库位树 + 占用情况。」以及关系段的红线：「**库位不能跨租户引用**，
 * 也不允许跨仓库。」
 *
 * <p><b>一个表、一个真相来源。</b>层级、容量、承重三列由 V105 补在 {@code warehouse_location}
 * 上 —— 不另建节点表，因为 stock_bucket 的复合外键指向的就是这张表，另建一张层级表会让
 * 「库位本身」与「库位的父子关系」分居两处，每条读库位的 SQL 都得记得 join。
 *
 * <p><b>出树用递归 CTE，占用用一个标量子查询。</b>两处纪律：
 *
 * <ol>
 *   <li>递归 CTE 只做**结构**（id / parent_id / depth / path），占用不 join 进来。
 *       在递归项里 join {@code stock_bucket} 会让同一个库位的每一行随 bucket 数重复，
 *       祖先的汇总被放大 N 次。
 *   <li>占用是**一个**标量子查询（按 {@code location_id} 收口），不是 join 展开 ——
 *       与 8.1.1.1/8.1.1.2 同一理由：库位/批次维度 join 会成笛卡尔积。
 * </ol>
 *
 * <p><b>防环与跨仓库挂父子由 DB 裁决，本类只负责把话说清楚。</b>V105 的
 * {@code guard_warehouse_location_tree} 触发器用递归 CTE 拒绝成环与超六层，
 * 复合外键 {@code warehouse_location_parent_fk} 拒绝跨租户与跨仓库的父子关系。
 * 应用层先查一次是为了给出可读的中文报错，**不是**权威判据 —— 绕过服务层直连 SQL
 * 也一样被拒。成环会让递归查询永不收敛，所以这一条必须是 DB 不变量而不是应用层 if。
 *
 * <p><b>租户谓词每条查询都写。</b>RLS（{@code app.current_tenant}）是第一道防线，
 * 显式 {@code tenant_id=} 是第二道：忘了绑定上下文时第二道让查询当场返回空，而不是全表。
 */
@Service
public class WarehouseLocationTreeService {

  /** 蓝图给的层级数（区 → 巷 → 排 → 架 → 层 → 格），与 V105 触发器的上限一致。 */
  static final int MAX_DEPTH = 6;

  /** 编码上限，与 V2 建表时的 varchar(64) 一致。 */
  private static final int CODE_MAX = 64;

  /** 名称上限，与 V2 建表时的 varchar(200) 一致。 */
  private static final int NAME_MAX = 200;

  /** 状态词表与 V2 的 CHECK 逐字一致。 */
  private static final List<String> STATUSES = List.of("ACTIVE", "INACTIVE");

  private final DSLContext sql;

  public WarehouseLocationTreeService(DSLContext sql) {
    this.sql = sql;
  }

  // ================================================================ 读：树

  /**
   * 库位树 + 占用情况。
   *
   * <p>仓库不存在或不属于本租户一律 404 —— 两者的可观测结果必须相同，否则「猜 id」
   * 就成了探测别的租户有没有仓库的侧信道（与 8.1.1.2 同一纪律）。
   */
  public LocationTree tree(UUID warehouseId) {
    requireWarehouse(warehouseId);

    // 结构：递归 CTE 只带层级字段。path 用 text 累积 '|code'，子树汇总靠它做前缀匹配。
    var rows =
        sql.fetch(
            """
            with recursive t as (
              select l.id, l.parent_id, l.tenant_id, l.code, l.name, l.type, l.status, l.pickable,
                     l.capacity, l.weight_limit, l.created_at, l.updated_at,
                     -- path 必须**显式 cast 成 text**：递归项里 t.path || '|' || c.code
                     -- 的结果是 text，而非递归项的 l.code 是 varchar(64)，PG 要求
                     -- 递归查询两分支的列类型一致（"type character varying(64) in
                     -- non-recursive term but type character varying overall"）。
                     0 as depth, l.code::text as path
                from warehouse_location l
               where l.tenant_id = current_setting('app.current_tenant', true)::uuid
                 and l.warehouse_id = ? and l.parent_id is null
              union all
              select c.id, c.parent_id, c.tenant_id, c.code, c.name, c.type, c.status, c.pickable,
                     c.capacity, c.weight_limit, c.created_at, c.updated_at,
                     t.depth + 1, t.path || '|' || c.code
                from warehouse_location c
                join t on t.id = c.parent_id
               where c.tenant_id = current_setting('app.current_tenant', true)::uuid
                 and c.warehouse_id = ?
            )
            select t.id, t.parent_id, t.code, t.name, t.type, t.status, t.pickable,
                   t.capacity, t.weight_limit, t.depth, t.path, t.created_at, t.updated_at,
                   -- 占用：**一个标量子查询**，按 location_id 收口。
                   -- 不 join stock_bucket：bucket 每一行都会让本行重复，祖先汇总被放大。
                   (select coalesce(sum(k.on_hand),0) from stock_bucket k
                     where k.tenant_id=t.tenant_id and k.location_id=t.id) as on_hand,
                   (select coalesce(sum(k.reserved),0) from stock_bucket k
                     where k.tenant_id=t.tenant_id and k.location_id=t.id) as reserved,
                   (select count(distinct k.sku_id) from stock_bucket k
                     where k.tenant_id=t.tenant_id and k.location_id=t.id) as sku_count,
                   (select count(*) from stock_bucket k
                     where k.tenant_id=t.tenant_id and k.location_id=t.id) as bucket_rows
              from t
             order by t.path
            """,
            warehouseId,
            warehouseId);

    // 先在 Java 侧按父子关系分组（rows 已按 path 排序：父先于子、同层按 code）。
    Map<UUID, List<org.jooq.Record>> byParent = new LinkedHashMap<>();
    for (org.jooq.Record r : rows) {
      byParent
          .computeIfAbsent(r.get("parent_id", UUID.class), k -> new ArrayList<>())
          .add(r);
    }

    // 子树占用：自底向上累加。rows 按 path 排序（父在子之前），倒序遍历即自底向上。
    Map<UUID, Occupancy> subtree = new HashMap<>();
    for (org.jooq.Record r : rows) {
      subtree.put(r.get("id", UUID.class), ownOccupancy(r));
    }
    List<org.jooq.Record> bottomUp = new ArrayList<>(rows);
    java.util.Collections.reverse(bottomUp);
    for (org.jooq.Record r : bottomUp) {
      UUID id = r.get("id", UUID.class);
      Occupancy roll = subtree.get(id);
      for (org.jooq.Record child : byParent.getOrDefault(id, List.of())) {
        roll = add(subtree.get(child.get("id", UUID.class)), roll);
      }
      subtree.put(id, roll);
    }

    // 输出**扁平**的层级行（不再嵌套 children）：springdoc 会丢掉自引用的 record 属性
    // （List<LocationNode> children 在生成的 spec 里整个消失），契约里没有 children 时
    // 前端按类型写了也收不到。depth 已经足够表达缩进层级。
    List<LocationNode> levels = new ArrayList<>(rows.size());
    for (org.jooq.Record r : rows) {
      levels.add(node(r, subtree.get(r.get("id", UUID.class))));
    }
    long rootCount = rows.stream().filter(r -> r.get("parent_id", UUID.class) == null).count();
    return new LocationTree(warehouseId, levels, rootCount, totals(rows, warehouseId), note());
  }

  /**
   * 子树占用 = 本节点直接占用 + 各子节点子树占用（自底向上叠加一次得出）。
   *
   * <p><b>容量利用率与超载在子树口径上恒为「无意义」，所以这里给 null / false。</b>
   * 用子节点的手数除以**本节点**的容量算出的比例没有物理含义：格子的容量管的是它自己那
   * 一格货，上层「排」的容量管的是整排。让界面显示一个没人能解释的百分比，比不给更糟。
   * 逐节点的 {@code occupied.capacityUtilization} 才是真数。
   *
   * <p>skuCount 这里也只加总而非去重：同一个 SKU 放在同一棵子树的两个格子里会被数两次。
   * 去重要把整棵树的 SKU 集合摊平才做得到，代价是一条额外查询；本模块的 SKU 数用途是
   * 「这个范围涉及几种商品」的量级参考，节点级 {@code occupied.skuCount} 是精确值。
   */
  private static Occupancy add(Occupancy child, Occupancy acc) {
    return new Occupancy(
        nz(acc.onHand()).add(nz(child.onHand())),
        nz(acc.reserved()).add(nz(child.reserved())),
        acc.skuCount() + child.skuCount(),
        acc.bucketRows() + child.bucketRows(),
        null,
        false);
  }

  /**
   * 单个库位的直接占用。
   *
   * <p>利用率在<b>容量已量过</b>时才给值：把「没量过」折成 0 的话，界面上
   * 「量过且空置」与「从没量过」会长得一样，两个都是 0%。
   */
  private static Occupancy ownOccupancy(org.jooq.Record r) {
    BigDecimal onHand = decimal(r, "on_hand");
    BigDecimal reserved = decimal(r, "reserved");
    return new Occupancy(
        onHand,
        reserved,
        count(r, "sku_count"),
        count(r, "bucket_rows"),
        utilization(onHand, r.get("capacity", BigDecimal.class)),
        r.get("capacity", BigDecimal.class) != null
            && nz(onHand).compareTo(r.get("capacity", BigDecimal.class)) > 0);
  }

  /**
   * 容量利用率，四位小数。
   *
   * <p>{@code null} 容量返回 {@code null}（没量过），而不是 0 —— 见 {@link Occupancy}。
   * scale 定为 4 且 {@link RoundingMode#HALF_UP}：不给 scale 的话 {@code divide} 会
   * 抛 ArithmeticException（无限小数），给了又必须定舍入方向。
   */
  private static BigDecimal utilization(BigDecimal onHand, BigDecimal capacity) {
    if (capacity == null) return null;
    if (capacity.compareTo(BigDecimal.ZERO) == 0) {
      // 量得容量为 0：在手 > 0 就是超载，比值没有意义（除零）。
      return nz(onHand).compareTo(BigDecimal.ZERO) > 0 ? null : BigDecimal.ZERO.setScale(4);
    }
    return nz(onHand).divide(capacity, 4, RoundingMode.HALF_UP);
  }

  private LocationNode node(org.jooq.Record r, Occupancy subtree) {
    LocationType type = LocationType.of(r.get("type", String.class));
    String status = r.get("status", String.class);
    return new LocationNode(
        r.get("id", UUID.class),
        r.get("parent_id", UUID.class),
        r.get("code", String.class),
        r.get("name", String.class),
        type,
        type.label(),
        status,
        statusLabel(status),
        Boolean.TRUE.equals(r.get("pickable", Boolean.class)),
        type.pickableByNature(),
        r.get("depth", Integer.class),
        r.get("capacity", BigDecimal.class),
        r.get("weight_limit", BigDecimal.class),
        ownOccupancy(r),
        subtree,
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  /** 整树合计。逐行扫描，不另开一条聚合 SQL —— 避免两处口径各算各的。 */
  private TreeTotals totals(List<org.jooq.Record> rows, UUID warehouseId) {
    long roots = rows.stream().filter(r -> r.get("parent_id", UUID.class) == null).count();
    long leaves = 0;
    long pickable = 0;
    long measured = 0;
    java.util.Set<UUID> parents = new java.util.HashSet<>();
    java.util.Set<UUID> skus = new java.util.HashSet<>();
    BigDecimal onHand = BigDecimal.ZERO;
    BigDecimal reserved = BigDecimal.ZERO;
    long skuRows = 0;
    Map<LocationType, Long> byType = new LinkedHashMap<>();

    for (org.jooq.Record r : rows) {
      UUID parent = r.get("parent_id", UUID.class);
      if (parent != null) parents.add(parent);
      if (Boolean.TRUE.equals(r.get("pickable", Boolean.class))) pickable++;
      if (r.get("capacity", BigDecimal.class) != null) measured++;
      onHand = onHand.add(nz(decimal(r, "on_hand")));
      reserved = reserved.add(nz(decimal(r, "reserved")));
      skuRows += count(r, "sku_count");
      byType.merge(LocationType.of(r.get("type", String.class)), 1L, Long::sum);
    }
    leaves = rows.stream().filter(r -> !parents.contains(r.get("id", UUID.class))).count();
    skus.addAll(distinctSkusInWarehouse(warehouseId));

    List<TypeBreakdown> types = new ArrayList<>();
    for (LocationType t : LocationType.values()) {
      long n = byType.getOrDefault(t, 0L);
      if (n > 0) types.add(new TypeBreakdown(t, t.label(), n));
    }

    return new TreeTotals(
        rows.size(),
        roots,
        leaves,
        pickable,
        measured,
        onHand,
        reserved,
        skus.size(),
        skuRows,
        types.size(),
        types);
  }

  /**
   * 本仓全部库位上涉及的 SKU（去重）。
   *
   * <p>一条查询扫完，而不是每个节点各查一次（{@code skuCount} 逐节点已在树查询里算过，
   * 这里只要去重后的全集）。逐节点求并集会让「整仓 SKU 数」随库位数线性放大查询次数。
   */
  private List<UUID> distinctSkusInWarehouse(UUID warehouseId) {
    if (warehouseId == null) return List.of();
    return sql.fetch(
            """
            select distinct k.sku_id from stock_bucket k
             where k.tenant_id = current_setting('app.current_tenant', true)::uuid
               and k.warehouse_id = ?
            """,
            warehouseId)
        .stream()
        .map(r -> r.get(0, UUID.class))
        .toList();
  }

  // ================================================================ 读：能力

  /**
   * 能力声明。
   *
   * <p>层级 / 容量 / 承重 / 占用四项由本模块交付；**自动分配（8.1.2.2）未交付**，
   * 拣货路径（8.3.2.1）未交付 —— 如实标 {@code false} 并写明归属模块，
   * 不给 0、不给空数组冒充「这个仓没有」。
   */
  public LocationTreeCapabilities capabilities() {
    return new LocationTreeCapabilities(
        true,
        true,
        true,
        true,
        false,
        "8.1.2.2",
        "8.3.2.1",
        List.of(LocationType.values()),
        MAX_DEPTH,
        "层级、容量、承重与占用来自 warehouse_location 与 stock_bucket 的真实聚合；"
            + "库位自动分配与拣货路径尚未交付，本服务不提供替代口径。");
  }

  // ================================================================ 写

  /** 新建库位。父节点可空（null = 顶层根「区」）。 */
  @Transactional
  public LocationSaved create(UUID warehouseId, LocationCreateInput input) {
    requireWarehouse(warehouseId);
    String code = required(input.code(), CODE_MAX, "库位编码");
    String name = required(input.name(), NAME_MAX, "库位名称");
    LocationType type = input.type() == null ? LocationType.STORAGE : LocationType.of(input.type());
    UUID parentId = input.parentId();
    if (parentId != null) requireSameWarehouse(parentId, warehouseId, "父库位");
    BigDecimal capacity = nonNegative(input.capacity(), "容量");
    BigDecimal weight = nonNegative(input.weightLimit(), "承重上限");

    UUID id = UUID.randomUUID();
    sql.execute(
        """
        insert into warehouse_location
          (id,tenant_id,warehouse_id,code,name,type,status,pickable,parent_id,capacity,weight_limit)
        values (?,current_setting('app.current_tenant', true)::uuid,?,?,?,?,'ACTIVE',?,
                cast(? as uuid),cast(? as numeric),cast(? as numeric))
        """,
        id,
        warehouseId,
        code,
        name,
        type.name(),
        input.pickable() == null ? Boolean.TRUE : input.pickable(),
        // 三个可空列必须带 SQL 类型：jOOQ 绑定一个无类型的 null 时无法推断参数类型，
        // 抛 "Type class java.lang.Class is not supported in dialect POSTGRES"。
        // 根节点（parent_id=null）是**最常见的形状**，所以这不是边角情况。
        parentId == null ? org.jooq.impl.DSL.val((UUID) null, UUID.class)
            : org.jooq.impl.DSL.val(parentId, UUID.class),
        capacity == null ? org.jooq.impl.DSL.val((BigDecimal) null, BigDecimal.class)
            : org.jooq.impl.DSL.val(capacity, BigDecimal.class),
        weight == null ? org.jooq.impl.DSL.val((BigDecimal) null, BigDecimal.class)
            : org.jooq.impl.DSL.val(weight, BigDecimal.class));
    return new LocationSaved(id, code, parentId, "CREATED", "库位已建，层级与占用随树重算。");
  }

  /**
   * 改库位属性与层级。
   *
   * <p>层级调整（改 parent）走与新建同一条 DB 防线：跨仓库挂父子被复合外键拒、
   * 成环与超六层被触发器拒。{@code parentProvided=false} 表示调用方没带 parent 字段，
   * 保持原层级不动 —— 用 null 表达「升为根」会让「没带字段」与「升为根」变成同一件事。
   */
  @Transactional
  public LocationSaved update(UUID warehouseId, UUID locationId, LocationUpdateInput input) {
    requireWarehouse(warehouseId);
    var current = sql.fetchOne(
        """
        select code, name, parent_id, type, status, pickable from warehouse_location
         where tenant_id = current_setting('app.current_tenant', true)::uuid
           and warehouse_id = ? and id = ?
        """,
        warehouseId,
        locationId);
    if (current == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "库位不存在");

    String name = input.name() == null ? current.get("name", String.class)
        : required(input.name(), NAME_MAX, "库位名称");
    LocationType type =
        input.type() == null
            ? LocationType.of(current.get("type", String.class))
            : LocationType.of(input.type());
    String status = current.get("status", String.class);
    if (input.status() != null) {
      status = input.status().trim().toUpperCase(java.util.Locale.ROOT);
      if (!STATUSES.contains(status)) {
        throw new IllegalArgumentException("库位状态不在词表内：" + input.status());
      }
    }

    UUID parentId = current.get("parent_id", UUID.class);
    boolean moving = input.parentProvided() != null && input.parentProvided();
    if (moving) {
      parentId = input.parentId();
      if (parentId != null) {
        if (parentId.equals(locationId)) {
          throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "库位不能挂在自身之下");
        }
        requireSameWarehouse(parentId, warehouseId, "父库位");
        rejectCycle(locationId, parentId);
      }
    }

        // pickable 与 capacity/weight 一样**没带就保持原值**：改个名字不该顺手把
    // 「不可拣」重置成「可拣」（那是另一个决定）。需要显式开关时带 true / false。
    Object pickable =
        input.pickable() == null ? current.get("pickable", Boolean.class) : input.pickable();
    sql.execute(
        """
        update warehouse_location
           set name = ?, type = ?, status = ?, pickable = ?::boolean,
               parent_id = cast(? as uuid),
               capacity = cast(? as numeric), weight_limit = cast(? as numeric), updated_at = now()
         where tenant_id = current_setting('app.current_tenant', true)::uuid
           and warehouse_id = ? and id = ?
        """,
        name,
        type.name(),
        status,
        pickable,
        typedUuid(parentId),
        typedDecimal(input.capacity() == null ? null : nonNegative(input.capacity(), "容量")),
        typedDecimal(input.weightLimit() == null ? null : nonNegative(input.weightLimit(), "承重上限")),
        warehouseId,
        locationId);

    return new LocationSaved(
        locationId,
        current.get("code", String.class),
        parentId,
        moving ? "MOVED" : "UPDATED",
        moving ? "层级已调整，树与占用已重算。" : "属性已更新。");
  }

  /**
   * 删库位。
   *
   * <p>只允许删**叶子且空**的库位：有子节点会让子树从树上凭空消失，
   * 压着库存的库位删掉会让 stock_bucket 的事实指向不存在的库位。
   * 两道都是 DB 硬约束（parent_id 外键 NO ACTION + stock_bucket 复合外键），
   * 这里先查一遍只为把 409 的话说清楚。
   */
  @Transactional
  public void delete(UUID warehouseId, UUID locationId) {
    requireWarehouse(warehouseId);
    var row =
        sql.fetchOne(
            """
            select code,
                   (select count(*) from warehouse_location c
                     where c.tenant_id=current_setting('app.current_tenant', true)::uuid
                       and c.parent_id = l.id) as children,
                   (select count(*) from stock_bucket k
                     where k.tenant_id=current_setting('app.current_tenant', true)::uuid
                       and k.location_id = l.id) as buckets
              from warehouse_location l
             where l.tenant_id = current_setting('app.current_tenant', true)::uuid
               and l.warehouse_id = ? and l.id = ?
            """,
            warehouseId,
            locationId);
    if (row == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "库位不存在");
    if (count(row, "children") > 0) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "库位下仍有子节点，请先移走或删除子节点");
    }
    if (count(row, "buckets") > 0) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "库位上仍有库存，请先把货移走再删");
    }
    int deleted =
        sql.execute(
            """
            delete from warehouse_location
             where tenant_id = current_setting('app.current_tenant', true)::uuid
               and warehouse_id = ? and id = ?
            """,
            warehouseId,
            locationId);
    if (deleted == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "库位不存在");
  }

  // ================================================================ 内部

  /**
   * 防环：沿候选父节点的 parent 链上行，一旦走回自己就成环。
   *
   * <p><b>DB 的 {@code guard_warehouse_location_tree} 才是权威判据</b>，这里先查一次
   * 只是为了给出「挂到自己子孙之下」这句中文提示，而不是让用户撞一个裸的
   * check_violation（那在界面上只显示「数据与既有记录冲突」）。
   * 两道都要：只做应用层检查的话，绕过服务的写入口照样能挂出环。
   */
  private void rejectCycle(UUID locationId, UUID candidateParentId) {
    // fetchOne + get(0, ...)：jOOQ 的 fetchValue(sql, Class, args) 重载在本仓库的
    // jOOQ 版本上会把 Class 当成绑定值（报 "Type class java.lang.Class is not supported"）。
    var reached =
        sql.fetchOne(
            """
            with recursive up as (
              select l.id, l.parent_id from warehouse_location l
               where l.tenant_id = current_setting('app.current_tenant', true)::uuid
                 and l.id = ? and l.id <> ?
              union all
              select p.id, p.parent_id from warehouse_location p
                join up on up.parent_id = p.id
               where p.tenant_id = current_setting('app.current_tenant', true)::uuid
            )
            select exists(select 1 from up where up.id = ?) as reaches
            """,
            candidateParentId,
            locationId,
            locationId);
    if (reached != null && Boolean.TRUE.equals(reached.get(0, Boolean.class))) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "库位不能挂到它自己的子孙之下：那会让库位树成环、递归查询不再收敛");
    }
  }

  /** 仓库必须属于本租户，否则 404（与 8.1.1.2 同一套：不可区分「不存在」与「不属于你」）。 */
  private void requireWarehouse(UUID warehouseId) {
    var row =
        sql.fetchOne(
            """
            select 1 from warehouse
             where tenant_id = current_setting('app.current_tenant', true)::uuid and id = ?
            """,
            warehouseId);
    if (row == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "仓库不存在");
  }

  /**
   * 父库位必须落在同一个仓库。
   *
   * <p>跨租户与跨仓库都由 V105 的复合外键 {@code warehouse_location_parent_fk} 在 DB 层
   * 拒绝，这里先查是为了给出中文报错而不是裸 FK 违例。**两道都要**：
   * 只做应用层检查的话，绕过服务的写入口（运维脚本、其它服务的直连）就能挂出跨仓父子。
   */
  private void requireSameWarehouse(UUID parentId, UUID warehouseId, String what) {
    var parentRow =
        sql.fetchOne(
            """
            select warehouse_id from warehouse_location
             where tenant_id = current_setting('app.current_tenant', true)::uuid and id = ?
            """,
            parentId);
    var parentWarehouse = parentRow == null ? null : parentRow.get(0, UUID.class);
    if (parentWarehouse == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, what + "不存在");
    }
    if (!parentWarehouse.equals(warehouseId)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, what + "必须属于同一个仓库：库位不允许跨仓库挂接");
    }
  }

  private static String note() {
    return "层级由 warehouse_location.parent_id 维护，成环与超六层由 DB 触发器拒绝；"
        + "占用来自 stock_bucket 的实时聚合，手数与预占分列；容量未量过的库位利用率给 null 而不是 0。";
  }

  private static String statusLabel(String status) {
    return "ACTIVE".equals(status) ? "启用" : "停用";
  }

  private static String required(String raw, int max, String field) {
    if (raw == null || raw.isBlank()) throw new IllegalArgumentException(field + "不能为空");
    String v = raw.trim();
    if (v.length() > max) throw new IllegalArgumentException(field + "长度不能超过 " + max);
    return v;
  }

  private static BigDecimal nonNegative(BigDecimal value, String field) {
    if (value == null) return null;
    if (value.compareTo(BigDecimal.ZERO) < 0) {
      throw new IllegalArgumentException(field + "不能为负数");
    }
    return value;
  }

  /** 可空 UUID 的带类型绑定（根节点与「清空层级」都是 null 形状）。 */
  private static Object typedUuid(UUID value) {
    return value == null ? org.jooq.impl.DSL.val((UUID) null, UUID.class)
        : org.jooq.impl.DSL.val(value, UUID.class);
  }

  /** 可空 NUMERIC 的带类型绑定。 */
  private static Object typedDecimal(BigDecimal value) {
    return value == null ? org.jooq.impl.DSL.val((BigDecimal) null, BigDecimal.class)
        : org.jooq.impl.DSL.val(value, BigDecimal.class);
  }

  private static BigDecimal nz(BigDecimal v) {
    return v == null ? BigDecimal.ZERO : v;
  }

  /** NUMERIC 读回走 BigDecimal，不经过 double —— 0.1×3 必须是 0.3。 */
  private static BigDecimal decimal(org.jooq.Record r, String column) {
    return nz(r.get(column, BigDecimal.class));
  }

  private static long count(org.jooq.Record r, String column) {
    Long v = r.get(column, Long.class);
    return v == null ? 0L : v;
  }
}