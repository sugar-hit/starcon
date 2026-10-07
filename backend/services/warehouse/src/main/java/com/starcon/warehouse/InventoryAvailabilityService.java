package com.starcon.warehouse;

import static com.starcon.warehouse.WarehouseModels.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 3.5.2.2 BOM 展开消费的**批量可用量查询**端点。
 *
 * <p><b>这个端点为什么必须存在</b>（AGENTS.md「模块边界纪律」）：
 * BOM 事实在 catalog，库存事实在 warehouse。catalog 要算「需求量 − 可用 − 在途 = 缺口」，
 * 而 {@code inventory_balance} / {@code inventory_transfer} 是<b>本服务</b>的表。
 * 三条路可选：跨库 JOIN（明令禁止，会让两个服务的部署绑死在一个 PG 实例上）、
 * 跨库 FK / 视图（同上，且破坏「每张事实表只有一个写入所有者」）、授权 API（本文件选的第三条）。
 * 所以 catalog 侧通过 HTTP 调这里取数，<b>不在 catalog 库复制任何库存快照</b>。
 *
 * <p><b>口径全部来自 V1 迁移文件头，Java 侧只做求和，不二次推导</b>：
 *
 * <ul>
 *   <li><b>可用 available</b> = {@code Σ inventory_balance.available}，
 *       而 available 是生成列 {@code on_hand − reserved}，<b>没有第三个来源</b>。
 *       刻意<b>不含</b>在途：未到货的库存拿去预占就是超卖，而超卖不可逆。
 *   <li><b>在途 in_transit</b> = {@code Σ inventory_transfer.quantity WHERE status='IN_TRANSIT'}，
 *       仓库过滤走 {@code to_warehouse_id}（发往这仓的），不是 {@code from_warehouse_id}：
 *       抵扣的是「会到这仓来」的货，发出去的货只会让<b>来源仓</b>变少。
 * </ul>
 *
 * <p><b>租户隔离</b>：本服务不信任任何客户端头。租户由 {@link AccessService} 从已认证
 * 成员关系（identity）解析，再在事务里绑定 {@code app.current_tenant}，
 * 三张被查的表全部 FORCE RLS —— 即使查询漏写 {@code tenant_id=?}，
 * 数据库也会把不属于本租户的行全部挡掉。
 *
 * <p><b>为什么返回零而不是省略</b>：请求的 SKU 里可能有本租户一行的库存都没有。
 * 省略它，调用方看到的就是「少了一个 SKU」，无法区分「没库存」与「不存在」，
 * 于是只能把缺口当成未知 —— 而缺口正是 06 采购单要用的数。
 */
@Service
public class InventoryAvailabilityService {

  /**
   * 一次最多查多少个 SKU。
   *
   * <p>是<b>可用性</b>上限而不是数据库限制：BOM 展开树的最坏情况是
   * 每层 50 个组件（3.5.2.1 的上限）× 10 层 = 500 个 SKU，
   * 再多就是有人造了一棵没人核得对的树，而领错料要上仓库找回来。
   */
  public static final int MAX_SKUS = 500;

  private final DSLContext sql;

  public InventoryAvailabilityService(DSLContext sql) {
    this.sql = sql;
  }

  /**
   * 批量取可用与在途。
   *
   * <p>两次聚合查询（余额 + 在途）而不是一次 join：两者的聚合维度不同
   * （余额按 quality_status 展开、在途按 quality_status 展开但只取 IN_TRANSIT），
   * 用 left join 相乘会让在途被按质量状态重复计数。
   */
  public AvailabilityView availability(AvailabilityQuery query) {
    UUID tenantId = currentTenantId();
    List<UUID> skuIds = normalizeIds(query.skuIds());
    if (skuIds.isEmpty()) {
      throw bad("请至少传一个 SKU：可用量端点按 SKU 集合批量查，空集合没有可回答的问题");
    }
    if (skuIds.size() > MAX_SKUS) {
      throw bad(
          "一次最多查 "
              + MAX_SKUS
              + " 个 SKU，收到 "
              + skuIds.size()
              + " 个：BOM 展开树最坏是每层 50 个组件 × 10 层 = 500 个 SKU，"
              + "再多就不是人能核对的清单了，而领错料要上仓库找回来");
    }
    UUID warehouseId = query.warehouseId();
    if (warehouseId != null) {
      var exists =
          sql.fetchOne("select id from warehouse where tenant_id=? and id=?", tenantId, warehouseId);
      if (exists == null) {
        throw bad(
            "仓库在本租户不存在：按仓库过滤的可用量必须能指名一个真实存在的仓库，"
                + "否则「按这个仓算缺口」与「不按仓算」会给出同样的答案，"
                + "页面上的仓库筛选就变成一个不起作用的开关");
      }
    }

    String in = placeholders(skuIds.size());
    List<Object> binds = new ArrayList<>();
    binds.add(tenantId);
    binds.addAll(skuIds);
    String warehouseFilter = warehouseId == null ? "" : " and warehouse_id=?";
    Object[] args = binds.toArray();
    if (warehouseId != null) {
      List<Object> withWarehouse = new ArrayList<>(binds);
      withWarehouse.add(warehouseId);
      args = withWarehouse.toArray();
    }

    // 可用：只由生成列聚合而来（V1 第四节）。
    var balances =
        sql.fetch(
            "select sku_id,coalesce(sum(available),0) as available"
                + " from inventory_balance where tenant_id=? and sku_id in "
                + in
                + warehouseFilter
                + " group by sku_id",
            args);
    Map<UUID, BigDecimal> availableBySku = new LinkedHashMap<>();
    for (Record r : balances) {
      availableBySku.put(r.get("sku_id", UUID.class), nonNegative(r.get("available", BigDecimal.class)));
    }

    // 在途：按 to_warehouse_id 过滤（抵扣的是会到这仓来的货）。
    String transitFilter = warehouseId == null ? "" : " and to_warehouse_id=?";
    var transfers =
        sql.fetch(
            "select sku_id,coalesce(sum(quantity),0) as in_transit"
                + " from inventory_transfer where tenant_id=? and status='IN_TRANSIT' and sku_id in "
                + in
                + transitFilter
                + " group by sku_id",
            args);
    Map<UUID, BigDecimal> inTransitBySku = new LinkedHashMap<>();
    for (Record r : transfers) {
      inTransitBySku.put(
          r.get("sku_id", UUID.class), nonNegative(r.get("in_transit", BigDecimal.class)));
    }

    // sku_ref 只用于回显编码（catalog 那边自己就有 SKU 档案，这里是给人看的快照）。
    var codes =
        sql.fetch(
            "select id,code from sku_ref where tenant_id=? and id in " + in,
            concat(List.of(tenantId), skuIds).toArray());
    Map<UUID, String> codeBySku = new LinkedHashMap<>();
    for (Record r : codes) {
      codeBySku.put(r.get("id", UUID.class), r.get("code", String.class));
    }

    // 请求集合里**每一个** SKU 都回一行，没有库存行的给 0（见类注释）。
    List<AvailabilityRow> items = new ArrayList<>(skuIds.size());
    for (UUID skuId : skuIds) {
      items.add(
          new AvailabilityRow(
              skuId,
              codeBySku.get(skuId),
              availableBySku.getOrDefault(skuId, BigDecimal.ZERO),
              inTransitBySku.getOrDefault(skuId, BigDecimal.ZERO)));
    }
    return new AvailabilityView(
        warehouseId,
        skuIds.size(),
        items,
        "可用量只由 inventory_balance 的生成列（on_hand − reserved）聚合而来，**不含在途** —— "
            + "未到货的库存拿去预占就是超卖，而超卖不可逆。"
            + "在途量单独一列（status='IN_TRANSIT' 的调拨之和），"
            + (warehouseId == null
                ? "本次不限仓库：在途按全租户合计。"
                : "本次按仓库过滤：在途取的是**发往该仓**（to_warehouse_id）的货，"
                    + "而不是从该仓发出的货 —— 抵扣的是会到这仓来的量。")
            + "没有库存行的 SKU 返回 0 而不是缺席：缺席会让调用方分不清「没库存」与「不存在」，"
            + "而缺口正是 06 采购单要用的数。");
  }

  /** 去重但保留请求顺序：结果里每一行都要能对上调用方提交的那一行的位置。 */
  private static List<UUID> normalizeIds(List<UUID> skuIds) {
    List<UUID> out = new ArrayList<>();
    if (skuIds == null) return out;
    for (UUID id : skuIds) {
      if (id != null && !out.contains(id)) out.add(id);
    }
    return out;
  }

  /**
   * {@code IN} 列表的占位符，每个都带 {@code ::uuid}。
   *
   * <p>与 catalog 侧同一条纪律：jOOQ 把 UUID 按 varchar 送过去，
   * 而 {@code sku_id in (?)} 的单元素列表让 PG 无法反推参数类型。
   */
  private static String placeholders(int n) {
    return "(" + String.join(",", java.util.Collections.nCopies(n, "?::uuid")) + ")";
  }

  private static List<Object> concat(List<Object> head, List<?> tail) {
    List<Object> all = new ArrayList<>(head);
    all.addAll(tail);
    return all;
  }

  /** 负数截到 0：available 有 CHECK(available>=0)、在途有 CHECK(quantity>=0)，这里只是纵深兜底。 */
  private static BigDecimal nonNegative(BigDecimal value) {
    return value == null || value.signum() < 0 ? BigDecimal.ZERO : value;
  }

  private UUID currentTenantId() {
    return sql.fetchOne("select nullif(current_setting('app.current_tenant',true),'')::uuid")
        .get(0, UUID.class);
  }

  private static ResponseStatusException bad(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }
}
