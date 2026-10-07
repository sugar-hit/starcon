package com.starcon.warehouse;

import static com.starcon.warehouse.WarehouseModels.*;

import java.math.BigDecimal;
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
 * 7.1.1.1 库存总览：**纯 SQL 聚合视图**，不存任何独立汇总数据。
 *
 * <p>蓝图硬约束：「总览数字必须能下钻到明细且能核对上，总览只是聚合视图，不存独立数据」。
 * 这里是这句话的字面兑现：没有 overview_summary 之类的表，合计、每页行、分组统计图
 * **全部由同一条 FROM 上的聚合得出**，所以"总览合计 == 明细逐行求和"不是靠约定成立，
 * 而是结构上无从违反 —— 合计与行读的是同一批列，求和顺序不同也不会差出一个数。
 *
 * <p>口径（与 V1 迁移文件头一致，此处不重新定义）：
 *
 * <ul>
 *   <li>可用 available 只由 {@code inventory_balance.available} 生成列聚合而来，**没有第三个来源**。
 *   <li>在途**不计入** available —— 未到货的库存拿去预占就是超卖。
 *   <li>在手 = 在仓实物 + 在途。
 * </ul>
 *
 * <p>阈值解析顺序 SKU &gt; WAREHOUSE &gt; DEFAULT，全部走本库 jOOQ，不跨库。
 */
@Service
public class InventoryOverviewService {

  /** 单页最大行数：防止一次性把整个租户的余额行拉进 JVM。 */
  static final int MAX_PAGE_SIZE = 200;

  /** 分组统计里 SKU 维度的返回上限：汇总图不需要把所有 SKU 都画出来。 */
  static final int MAX_SKU_GROUPS = 20;

  /** 余额表的固定 FROM。质量状态是**本服务**的表，所以这里可以放心 JOIN。 */
  private static final String FROM =
      clause(
          """
          from inventory_balance b
          join sku_ref s on s.tenant_id=b.tenant_id and s.id=b.sku_id
          join warehouse w on w.tenant_id=b.tenant_id and w.id=b.warehouse_id
          join legal_entity_ref le on le.tenant_id=b.tenant_id and le.id=b.legal_entity_id
          join inventory_quality_status q on q.tenant_id=b.tenant_id and q.id=b.quality_status_id
          """);

  /**
   * 行的在途量表达式。
   *
   * <p>在途按**收货仓**归属，这样"这批货到哪个仓"在行上直接看得见；按发货仓归属会让
   * 总览显示"某个仓有在途"，而货其实在去这个仓的路上，两种口径都会让运营算错可用量。
   * 同一行里它被 SELECT 与 ORDER BY 共用，避免两处口径漂移。
   */
  private static final String IN_TRANSIT =
      """
       (select coalesce(sum(t.quantity),0) from inventory_transfer t
         where t.tenant_id=b.tenant_id and t.sku_id=b.sku_id
           and t.legal_entity_id=b.legal_entity_id
           and t.quality_status_id=b.quality_status_id
           and t.to_warehouse_id=b.warehouse_id
           and t.status='IN_TRANSIT')
       """;

  /**
   * 文本块片段的前导空格兜底。
   *
   * <p>Java 文本块会丢掉 {@code """} 之后的那一个换行（这是 JLS 的 incidental whitespace 规则），
   * 于是 {@code FROM} 常量实际上**不带前导换行**。直接拼在 {@code " as in_transit"} 后面会得到
   * {@code as in_transitfrom inventory_balance b} —— 一个只在运行时才炸的语法错误。
   * 这里统一给每个片段补一个前导空格，让片段之间靠常量本身就能安全拼接。
   */
  private static String clause(String fragment) {
    return " " + fragment.stripLeading();
  }

  /** 排序键白名单：排序键来自 query string，只允许这四个。 */
  private static final List<String> SORTS =
      List.of("ON_HAND", "AVAILABLE", "IN_TRANSIT", "SKU_CODE");

  /** 阈值解析顺序，与 7.2.2 扩展后的优先级保持单一来源。 */
  static final List<String> POLICY_SCOPES = List.of("SKU", "CATEGORY", "WAREHOUSE", "DEFAULT");

  private final DSLContext sql;
  private final CatalogProjectionPort projections;

  public InventoryOverviewService(DSLContext sql, CatalogProjectionPort projections) {
    this.sql = sql;
    this.projections = projections;
  }

  // ------------------------------------------------------------ 能力声明

  /**
   * 跨服务筛选维度接入情况。前端据此**禁用**不可用的筛选项并说明归属服务，
   * 而不是让用户填完再收一个 422。
   */
  public List<DimensionCapability> capabilities() {
    return Arrays.stream(CatalogProjectionPort.Dimension.values())
        .map(
            d ->
                new DimensionCapability(
                    d.name(),
                    d.label(),
                    d.owner(),
                    projections.supports(d),
                    projections.supports(d) ? "已接入" + d.owner() + "授权读取" : d.ownerNote()))
        .toList();
  }

  // ------------------------------------------------------------ 总览

  /** 总览查询。纯聚合：不写任何表，也不读任何预先算好的汇总。 */
  public InventoryOverview overview(OverviewQuery q) {
    requireAvailableDimensions(q);
    int size = Math.min(MAX_PAGE_SIZE, Math.max(1, q.size() == null ? 50 : q.size()));
    int page = Math.max(0, q.page() == null ? 0 : q.page());
    var where = new ArrayList<String>();
    var binds = new ArrayList<Object>();
    conditions(q, where, binds);
    String sqlWhere = where.isEmpty() ? "" : " where " + String.join(" and ", where);
    // 超储筛选依赖阈值列，WHERE 里要用到 pol，所以 lateral 必须在**基础表之后** join
    // （LATERAL 子查询引用 b.*，b 必须先在 FROM 里出现）。
    String from = FROM + policyLateral();

    long total = sql.fetchOne("select count(*)" + from + sqlWhere, binds.toArray()).get(0, Long.class);

    var rows =
        sql.fetch(
            "select b.id,b.sku_id,b.warehouse_id,b.legal_entity_id,b.quality_status_id,"
                + "s.code as sku_code,s.name as sku_name,"
                + "w.code as warehouse_code,w.name as warehouse_name,"
                + "le.code as legal_entity_code,le.name as legal_entity_name,"
                + "q.code as quality_status_code,q.name as quality_status_name,q.role,"
                + "b.on_hand,b.reserved,b.available,"
                + "case when q.role='SELLABLE' then b.on_hand else 0 end as sellable,"
                + "case when q.role='PENDING_INSPECTION' then b.on_hand else 0 end as pending_inspection,"
                + "case when q.role='DEFECTIVE' then b.on_hand else 0 end as defective,"
                + "b.available<=0 as zero_stock,"
                + "b.version,b.updated_at,"
                + "pol.max_on_hand,pol.scope_type,"
                + IN_TRANSIT
                + " as in_transit"
                + from
                + sqlWhere
                + order(q)
                + " limit ? offset ?",
            append(binds, size, (long) page * size).toArray());

    // 合计与分组从**同一条 FROM** 再聚合一次，而不是把当前页行在 Java 里加起来：
    // 后者算的是"这一页的和"，翻页时合计会跟着变，守恒式也就无从复核。
    return new InventoryOverview(
        summary(from, sqlWhere, binds),
        rows.stream().map(this::row).toList(),
        groups(from, sqlWhere, binds),
        capabilities(),
        total,
        page,
        size);
  }

  /**
   * 合计（含零库存 / 超储 / 未设阈值三个计数）。
   *
   * <p>超储判定用**该行自己的**在手量与解析出的 max_on_hand，不用"整租户合计 vs 合计阈值"：
   * 阈值挂在 SKU / 仓上，用合计比会把"这个 SKU 超储"说成"整个租户超储"。
   */
  private OverviewSummary summary(String from, String sqlWhere, List<Object> binds) {
    Record r =
        sql.fetchOne(
            "select coalesce(sum(b.on_hand),0) as on_hand,"
                + " coalesce(sum(b.reserved),0) as reserved,"
                + " coalesce(sum(b.available),0) as available,"
                + " coalesce(sum(case when q.role='SELLABLE' then b.on_hand else 0 end),0) as sellable,"
                + " coalesce(sum(case when q.role='PENDING_INSPECTION' then b.on_hand else 0 end),0) as pending,"
                + " coalesce(sum(case when q.role='DEFECTIVE' then b.on_hand else 0 end),0) as defective,"
                + " coalesce(sum(" + IN_TRANSIT + "),0) as in_transit,"
                + " count(*) as row_count,"
                + " count(*) filter (where b.available<=0) as zero_rows,"
                // 超储 = 已设阈值且在手超过阈值。未设阈值的行既不算超储，也不算"查过没超"，
                // 单独计数：0 与"没查过"含义不同，混在一起界面会骗人。
                + " count(*) filter (where pol.max_on_hand is not null and b.on_hand>pol.max_on_hand) as over_rows,"
                + " count(*) filter (where pol.max_on_hand is null) as unset_rows"
                + from
                + sqlWhere,
            binds.toArray());

    var onHandAtWarehouse = decimal(r, "on_hand");
    var reserved = decimal(r, "reserved");
    var available = decimal(r, "available");
    var inTransit = decimal(r, "in_transit");
    var sellable = decimal(r, "sellable");
    var pending = decimal(r, "pending");
    var defective = decimal(r, "defective");
    var onHand = onHandAtWarehouse.add(inTransit);

    // 守恒式当场复核：口径漂移是最难事后发现的错误，所以在这里直接判定，
    // 让集成测试能断言它，而不是让用户对着两个对不上的数字猜。
    return new OverviewSummary(
        onHandAtWarehouse,
        onHand,
        reserved,
        available,
        inTransit,
        sellable,
        pending,
        defective,
        r.get("row_count", Long.class),
        r.get("zero_rows", Long.class),
        r.get("over_rows", Long.class),
        r.get("unset_rows", Long.class),
        new BalanceCheck(
            available.compareTo(onHandAtWarehouse.subtract(reserved)) == 0,
            onHand.compareTo(sellable.add(pending).add(defective).add(inTransit)) == 0));
  }

  /** 分组统计：仓库 / 法人 / 质量角色 / SKU 四个维度，全部来自同一份余额数据。 */
  private List<OverviewGroup> groups(String from, String sqlWhere, List<Object> binds) {
    var out = new ArrayList<OverviewGroup>();
    out.addAll(group(from, "WAREHOUSE", "b.warehouse_id", "w.code||' '||w.name", sqlWhere, binds, 0));
    out.addAll(group(from, "LEGAL_ENTITY", "b.legal_entity_id", "le.code||' '||le.name", sqlWhere, binds, 0));
    out.addAll(group(from, "QUALITY_ROLE", "null::uuid", "q.name", sqlWhere, binds, 0));
    out.addAll(group(from, "SKU", "b.sku_id", "s.code||' '||s.name", sqlWhere, binds, MAX_SKU_GROUPS));
    return out;
  }

  private List<OverviewGroup> group(
      String from,
      String dimension,
      String keyExpr,
      String labelExpr,
      String sqlWhere,
      List<Object> binds,
      int limit) {
    var rows =
        sql.fetch(
            "select " + keyExpr + " as gkey," + labelExpr + " as glabel,"
                + " coalesce(sum(b.on_hand),0) as on_hand, coalesce(sum(b.reserved),0) as reserved,"
                + " coalesce(sum(b.available),0) as available,"
                + " coalesce(sum(case when q.role='SELLABLE' then b.on_hand else 0 end),0) as sellable,"
                + " coalesce(sum(case when q.role='PENDING_INSPECTION' then b.on_hand else 0 end),0) as pending,"
                + " coalesce(sum(case when q.role='DEFECTIVE' then b.on_hand else 0 end),0) as defective,"
                + " coalesce(sum(" + IN_TRANSIT + "),0) as in_transit,"
                + " count(*) as row_count,"
                + " count(*) filter (where b.available<=0) as zero_rows,"
                + " count(*) filter (where pol.max_on_hand is not null and b.on_hand>pol.max_on_hand) as over_rows"
                + from
                + sqlWhere
                + " group by " + keyExpr + "," + labelExpr
                // 排序键末尾恒定追加 gkey：同数量时分组顺序稳定，汇总图不会在两次刷新间跳。
                + " order by sum(b.on_hand) desc,gkey"
                + (limit > 0 ? " limit " + limit : ""),
            binds.toArray());
    return rows.stream()
        .map(
            r ->
                new OverviewGroup(
                    dimension,
                    r.get("gkey", UUID.class),
                    r.get("glabel", String.class),
                    decimal(r, "on_hand").add(decimal(r, "in_transit")),
                    decimal(r, "reserved"),
                    decimal(r, "available"),
                    decimal(r, "in_transit"),
                    decimal(r, "sellable"),
                    decimal(r, "pending"),
                    decimal(r, "defective"),
                    r.get("row_count", Long.class),
                    r.get("zero_rows", Long.class),
                    r.get("over_rows", Long.class)))
        .toList();
  }

  // ------------------------------------------------------------ 条件与排序

  /** 依赖未接入上游的筛选条件显式拒绝（422），不静默返回空集。 */
  private void requireAvailableDimensions(OverviewQuery q) {
    if (has(q.categoryIds()))
      throw new CatalogProjectionPort.DimensionUnavailableException(
          CatalogProjectionPort.Dimension.CATEGORY);
    if (has(q.brandIds()))
      throw new CatalogProjectionPort.DimensionUnavailableException(
          CatalogProjectionPort.Dimension.BRAND);
    if (has(q.shopIds()))
      throw new CatalogProjectionPort.DimensionUnavailableException(
          CatalogProjectionPort.Dimension.SHOP);
  }

  private static boolean has(List<UUID> ids) {
    return ids != null && !ids.isEmpty();
  }

  /** 拼 WHERE。所有值都走绑定，标识符只用常量白名单。 */
  private void conditions(OverviewQuery q, List<String> where, List<Object> binds) {
    if (q.warehouseId() != null) {
      where.add("b.warehouse_id = ?");
      binds.add(q.warehouseId());
    }
    if (q.legalEntityId() != null) {
      where.add("b.legal_entity_id = ?");
      binds.add(q.legalEntityId());
    }
    if (q.skuId() != null) {
      where.add("b.sku_id = ?");
      binds.add(q.skuId());
    }
    if (q.keyword() != null && !q.keyword().isBlank()) {
      // 关键字命中范围刻意含仓库与法人编码：总览最常见的用法就是"找某一个仓/某一个主体的货"。
      where.add(
          "(s.code ilike ? or s.name ilike ? or w.code ilike ? or w.name ilike ?"
              + " or le.code ilike ? or le.name ilike ? or q.name ilike ?)");
      String k = "%" + q.keyword().trim() + "%";
      for (int i = 0; i < 7; i++) binds.add(k);
    }
    if (Boolean.TRUE.equals(q.zeroStock())) where.add("b.available<=0");
    if (Boolean.FALSE.equals(q.zeroStock())) where.add("b.available>0");
    if (Boolean.TRUE.equals(q.overstock()))
      where.add("pol.max_on_hand is not null and b.on_hand>pol.max_on_hand");
    if (Boolean.FALSE.equals(q.overstock()))
      where.add("(pol.max_on_hand is null or b.on_hand<=pol.max_on_hand)");
  }

  private String order(OverviewQuery q) {
    String sort = q.sort() == null ? "ON_HAND" : q.sort().toUpperCase(Locale.ROOT);
    if (!SORTS.contains(sort)) {
      throw new IllegalArgumentException("不支持的排序键：" + q.sort());
    }
    // 决胜键恒定追加 b.id：同数量时翻页不会错位或重复。
    return switch (sort) {
      case "AVAILABLE" -> " order by b.available desc,b.id";
      case "IN_TRANSIT" -> " order by " + IN_TRANSIT + " desc,b.id";
      case "SKU_CODE" -> " order by s.code,b.id";
      default -> " order by b.on_hand desc,b.id";
    };
  }

  private String policyLateral() {
    return clause(
        """
        left join lateral (
          select p.max_on_hand, p.scope_type
            from inventory_policy p
           where p.tenant_id=b.tenant_id and p.enabled
             and ((p.scope_type='SKU' and p.scope_key=b.sku_id::text)
               or (p.scope_type='WAREHOUSE' and p.scope_key=b.warehouse_id::text)
               or (p.scope_type='DEFAULT' and btrim(p.scope_key)=''))
           order by array_position(array['SKU','WAREHOUSE','DEFAULT']::text[],p.scope_type)
           limit 1
        ) pol on true
        """);
  }

  // ------------------------------------------------------------ 行组装

  private OverviewRow row(Record r) {
    var onHand = decimal(r, "on_hand");
    var maxOnHand = r.get("max_on_hand", BigDecimal.class);
    var skuId = r.get("sku_id", UUID.class);
    var warehouseId = r.get("warehouse_id", UUID.class);
    var legalEntityId = r.get("legal_entity_id", UUID.class);
    var qualityStatusId = r.get("quality_status_id", UUID.class);
    return new OverviewRow(
        r.get("id", UUID.class),
        skuId,
        r.get("sku_code", String.class),
        r.get("sku_name", String.class),
        warehouseId,
        r.get("warehouse_code", String.class),
        r.get("warehouse_name", String.class),
        legalEntityId,
        r.get("legal_entity_code", String.class),
        r.get("legal_entity_name", String.class),
        qualityStatusId,
        r.get("quality_status_code", String.class),
        r.get("quality_status_name", String.class),
        QualityRole.valueOf(r.get("role", String.class)),
        onHand,
        decimal(r, "reserved"),
        decimal(r, "available"),
        decimal(r, "in_transit"),
        decimal(r, "sellable"),
        decimal(r, "pending_inspection"),
        decimal(r, "defective"),
        Boolean.TRUE.equals(r.get("zero_stock", Boolean.class)),
        maxOnHand != null,
        maxOnHand,
        r.get("scope_type", String.class),
        maxOnHand != null && onHand.compareTo(maxOnHand) > 0,
        r.get("version", Long.class),
        r.get("updated_at", OffsetDateTime.class),
        // 7.1.1.2 路由契约：把当前行的全部维度编进去，明细落地后直接可用。
        // 本期**不**创建该页面、界面上也不放死链，路由待落地记在 docs/backlog/。
        detailHref(skuId, warehouseId, legalEntityId, qualityStatusId));
  }

  /** 指向 7.1.1.2 的下钻契约（带全部行级维度）。 */
  static String detailHref(UUID skuId, UUID warehouseId, UUID legalEntityId, UUID qualityStatusId) {
    return "/inventory?skuId="
        + skuId
        + "&warehouseId="
        + warehouseId
        + "&legalEntityId="
        + legalEntityId
        + "&qualityStatusId="
        + qualityStatusId;
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
