import { InventoryRiskWorkspace } from '@/components/dashboard/inventory-risk-workspace';

/**
 * 1.1.3.1 库存风险。
 *
 * <p>卷 01「工作台与门户」的**风险速览**里的一格：集中呈现缺货、超卖风险、呆滞、超储
 * 四类库存问题，区分本地仓 / 海外仓 / FBA / 在途。
 *
 * <p>路由放在 `/dashboard/inventory-risk`：本模块在台账里的 L3 锚点路由是「—」，
 * 意味着它没有既定的蓝图路由，但**必须是真实可访问的页面**而不是占位页 ——
 * 「—」锚点的模块做成真实工作区，入口挂在对应业务区。库存风险对应的是库存业务区，
 * 所以挂在 `/dashboard/inventory-risk` 下，同时保留「这是工作台的一格」这层语义。
 *
 * <p>页面是**只读**的：一切状态变更回到归属系统（06 采购单 / 07 调拨 / 07 补货建议）完成。
 */
export default function InventoryRiskPage() {
  return <InventoryRiskWorkspace />;
}
