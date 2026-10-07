import { InventoryLotWorkspace } from '@/components/inventory/inventory-lot';

/**
 * 7.3.1.1 批次管理。
 *
 * <p>蓝图：「管理批次（lot）：批号、生产日期、失效日期、来源采购单/供应商，
 * 支持按批次查询库存与流向追溯」，输出「批次档案 + 批次库存分布 + 出入库历史」。
 *
 * <p>页面上的三个数字口径：批次库存分布真从 warehouse 服务的 `stock_bucket` 按批次聚合；
 * 出入库历史复用 7.1.2.1 库存流水台账的分录查询（页面必须显示后端返回的归属口径）；
 * 来源采购单/供应商是跨服务定位键，本服务不联查采购(6)/收货(8)的表。
 *
 * <p>批次与序列号是两个独立实体（序列号级库存属 7.3.1.2），本页不出现任何序列号字段。
 */
export default function InventoryLotsPage() {
  return <InventoryLotWorkspace />;
}
