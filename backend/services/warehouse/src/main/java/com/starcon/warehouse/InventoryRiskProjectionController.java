package com.starcon.warehouse;

import static com.starcon.warehouse.InventoryRiskProjectionModels.*;

import java.util.UUID;
import java.util.function.Function;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 1.1.3.1 库存风险的只读投影端点（卷 01 工作台与门户）。
 *
 * <p>命名空间 {@code /warehouse/inventory-risk}。
 *
 * <p><b>为什么在 warehouse 而不是卷 01 自己开一个服务</b>：卷 01 的定位写死了「后端归属：前端聚合层 + 各服务的只读投影
 * （<b>不新增业务服务</b>）」。本端点投影的每一张表（{@code stock_bucket} / {@code inventory_policy} /
 * {@code inventory_aging_policy} / {@code reservation_allocation} / {@code inventory_transfer}）都是 warehouse 服务的
 * 事实表，所以只读投影必须落在 warehouse —— 跨服务读对方的表被 AGENTS.md 明令禁止。
 *
 * <p><b>两个端点</b>：
 *
 * <ul>
 *   <li>{@code GET /warehouse/inventory-risk/projection} —— (仓 × SKU) 的库存事实投影（真分页 + 真筛选）
 *   <li>{@code GET /warehouse/inventory-risk/capabilities} —— 能力声明（哪些输入接通了、哪些没接通及归属模块）
 * </ul>
 *
 * <p><b>权限码统一 {@code warehouse.inventory.read}</b>：本端点只读，不写任何表 —— 与 7.1.1.2 / 7.1.1.3 同源同权。
 * 同一个数从库存明细页读出来与从工作台读出来必须是同一个数，权限一分为二就会诱导出两个口径。
 *
 * <p><b>刻意没有 {@code at} / {@code asOf} 参数</b>：本端点不存历史快照，回看一个时刻没有定义。
 */
@RestController
@RequestMapping("/warehouse/inventory-risk")
public class InventoryRiskProjectionController {

  private final AccessService access;
  private final InventoryRiskProjectionService projections;

  public InventoryRiskProjectionController(
      AccessService access, InventoryRiskProjectionService projections) {
    this.access = access;
    this.projections = projections;
  }

  /** 统一入口：先向 identity 确认身份与权限（租户**不**来自客户端头），再绑定 RLS 上下文。 */
  private <T> T call(String bearer, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "warehouse.inventory.read");
    return access.run(actor, "warehouse.inventory.read", () -> work.apply(actor));
  }

  /**
   * (仓 × SKU) 的库存事实投影。
   *
   * <p>返回的行里带齐了四类风险判定所需的<b>事实与阈值</b>（可用量 / 在手量 / 预占 / 库龄 / 阈值与档位 /
   * 活跃预占 / 在途），判定本身由卷 01 的前端聚合层完成 —— 跨服务聚合在那里做，本服务不越界。
   */
  @GetMapping("/projection")
  public RiskProjectionPage projection(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID warehouseId,
      @RequestParam(required = false) UUID skuId,
      @RequestParam(required = false) String warehouseKind,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "100") int size) {
    return call(
        auth,
        a ->
            projections.projection(
                new ProjectionQuery(warehouseId, skuId, warehouseKind, keyword, page, size)));
  }

  /**
   * 能力声明：哪些输入接通了、哪些没接通及归属模块。
   *
   * <p>日均销与采购到货如实报 {@code available=false} + 归属模块 + 影响 —— 它们在 trade 服务，
   * 由卷 01 的前端聚合层读取后合并，本服务不跨库读。
   */
  @GetMapping("/capabilities")
  public RiskProjectionCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, a -> projections.capabilities());
  }
}
