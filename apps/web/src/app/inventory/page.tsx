import { InventoryWorkspace } from '@/components/inventory/inventory-workspace';

/**
 * 7.1.1.1 库存总览。
 *
 * <p>总览只是聚合视图：页面上的每个数字都来自 warehouse 服务对 inventory_balance 的
 * 一次纯 SQL 聚合，本服务不存任何汇总数据。行点击在页内就地展开仓库级 / 状态级构成，
 * 7.1.1.2 明细路由待落地（记在 docs/backlog/），所以界面上不放死链。
 */
export default function InventoryPage() {
  return <InventoryWorkspace />;
}
