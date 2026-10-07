import { InventoryInTransitWorkspace } from '@/components/inventory/inventory-in-transit';

/**
 * 7.5.1.2 在途库存。
 *
 * <p>蓝图：「跟踪所有在途库存（调拨在途、头程在途、采购在途、FBA 发货在途），含起止仓、批次、
 * 数量、预计到达」，输出「在途库存清单 + 到达预警（逾期未达）」。
 *
 * <p>页面把四类在途放在**同一个口径**下：缺口计算要扣的「仍在途」如果漏了任何一类，
 * 就会直接变成重复补货。逾期是按 ETA 与此刻算出来的档位，没有人工可写的标记列。
 */
export default function InventoryTransitPage() {
  return <InventoryInTransitWorkspace />;
}