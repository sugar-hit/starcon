import { WarehouseLocationUtilizationWorkspace } from '@/components/warehouse/warehouse-location-utilization-workspace';

/**
 * 8.5.2.1 库位利用率。
 *
 * <p>蓝图：「分析库位占用：容量利用率、周转率、空置库位、爆满库位、低效库位，给出调整建议。
 * 输入：库位与库存占用；出库频次。输出：利用率报表 + 热力图 + 建议。」关系段：「上游 = 8.1.2.1；
 * 下游 = 8.1.2.2 自动分配优化。库位热力数据同时优化拣货路径（高频商品放近出口），一举两得。」
 *
 * <p>路由挂在 8.1.1.2 详情页之下（{@code /warehouse/facilities/[id]/utilization}），与 8.1.2.1
 * 库位树同层。理由与库位树一致：本页**读的是容量**（8.1.2.1 的 V105 补的
 * {@code warehouse_location.capacity}），所以它必须挂在同一个仓库上下文里；
 * 而它是<b>只读分析</b>，与库位树的<b>维护</b>是两种语义，所以独立路由而不是并进去。
 *
 * <p>这是一个真实工作区而不是占位页：容量利用率、周转率、空置/爆满/低效库位、
 * <b>真数据驱动的热力图</b>（色阶来自每个库位的真实利用率或在手量相对值，并标出强度来源）、
 * 逐条调整建议（含落地归属模块）、逐 SKU 明细，全部来自 PostgreSQL 16 上真实落库的查询。
 * 加载 / 空 / 错误三态分开渲染。
 */
export default async function WarehouseLocationUtilizationPage({
  params,
}: {
  params: Promise<{ id: string }>;
}) {
  const resolvedParams = await params;
  return <WarehouseLocationUtilizationWorkspace warehouseId={resolvedParams.id} />;
}
