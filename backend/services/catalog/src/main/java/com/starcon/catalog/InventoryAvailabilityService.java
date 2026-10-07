package com.starcon.catalog;

import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * catalog 侧读 warehouse 可用量的入口（3.5.2.2 BOM 展开）。
 *
 * <p>构造方式与本服务既有的 {@link AccessService}（读 identity）完全同构：
 * {@link org.springframework.web.client.RestClient} + {@code @Value} 读地址与内部密钥。
 * 跨服务调用在本仓库只有这一种写法，不为 warehouse 另起一套客户端框架。
 *
 * <p><b>为什么必须是 HTTP 而不是跨库 SQL</b>（AGENTS.md「模块边界纪律」）：
 * 库存事实在 warehouse 服务的 {@code inventory_balance} / {@code inventory_transfer}，
 * BOM 事实在 catalog。跨库 JOIN 会把两个服务的部署绑死在同一个 PG 实例上；
 * 跨库 FK / 视图同理；而且一旦两边都往库存表写，就出现了两个写入所有者。
 * 所以 catalog <b>只</b>通过 warehouse 的授权端点取数，本库不存在任何库存副本。
 *
 * <p><b>租户上下文的传递方式</b>：把调用者的 {@code Authorization} 头原样透传给 warehouse，
 * 由它向 identity 确认已认证成员关系、解析出租户，并在自己事务里绑定
 * {@code app.current_tenant} 受 RLS 强制隔离。
 * <b>本服务不发明 {@code X-Tenant-Id} 之类的头传租户</b> —— 那是一条「客户端自称租户」的旁路，
 * 正是 AGENTS.md 明令禁止的（租户 ID 由已认证用户的成员关系解析，不信任客户端头）。
 */
@Service
public class InventoryAvailabilityService {
  private final InventoryAvailabilityPort port;

  public InventoryAvailabilityService(
      @Value("${WAREHOUSE_URL:}") String warehouseUrl, @Value("${INTERNAL_KEY}") String key) {
    // 允许为空：其余 14 个 catalog 集成测试不碰展开路径，不该被一个它们用不上的配置项
    // 卡在启动失败上。真正调用时（availability()）才拒绝并说清原因 ——
    // 「启动就炸」与「用到时炸」在这里的差别只是报错时机，两者都必须拒绝。
    this.port =
        warehouseUrl == null || warehouseUrl.isBlank()
            ? null
            : new InventoryAvailabilityPort.HttpInventoryAvailabilityPort(warehouseUrl, key);
  }

  /**
   * 批量取可用与在途。
   *
   * @param bearer 调用者的 Authorization 头，<b>必须透传</b>：丢了它 warehouse 无从解析租户
   * @param skuIds 整棵展开树上出现过的全部 SKU（叶子 + 中间层）
   * @param warehouseId 仓库过滤；null = 全仓合计
   */
  public InventoryAvailabilityPort.AvailabilityResult availability(
      String bearer, List<UUID> skuIds, UUID warehouseId) {
    if (port == null) {
      throw new InventoryAvailabilityPort.AvailabilityUnavailableException(
          "库存服务地址未配置（WAREHOUSE_URL 为空）：BOM 展开需要「当前可用」与「在途」两个数，"
              + "拿不到时不能把它们当成 0 —— 那会让每个组件都显示缺货，"
              + "进而生成一张全是虚假缺口的采购单。请配置 WAREHOUSE_URL 后重试");
    }
    return port.availability(bearer, skuIds, warehouseId);
  }
}
