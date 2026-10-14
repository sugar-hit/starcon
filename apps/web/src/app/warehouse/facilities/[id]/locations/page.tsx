import { WarehouseLocationTreeWorkspace } from '@/components/warehouse/warehouse-location-tree-workspace';

/**
 * 8.1.2.1 库位树。
 *
 * <p>蓝图：「维护库位层级与属性（区 → 巷 → 排 → 架 → 层 → 格），支持容量、承重、
 * 类型（存储/暂存/待检/残次/冻结）、是否可拣。输入：库位增删改与层级调整；防环校验。
 * 输出：库位树 + 占用情况。」以及「库位不能跨租户引用，也不允许跨仓库」。
 *
 * <p>路由挂在 8.1.1.2 详情页之下（{@code /warehouse/facilities/[id]/locations}）而不是新开
 * 一个 L3：蓝图里 8.1「仓库与库位」的前端入口就是 {@code /warehouse/facilities}，
 * 库位树是它下面的一级子功能（ADR-0005：L3 子功能按角色配 CRUD，同一张 page_resource）。
 * 它与 8.1.1.2 的库位**清单**是两个形状 —— 清单是分页扁平列表，树保父子关系 ——
 * 所以是独立路由而不是并进详情页。
 *
 * <p>这是一个真实工作区而不是占位页：层级树、容量承重、六类类型、是否可拣、增删改与
 * 层级调整、防环与跨仓库挂接的拒绝提示、以及来自 stock_bucket 的实时占用，
 * 全部来自 PostgreSQL 16 上真实落库的查询。加载 / 空 / 错误三态分开渲染。
 */
export default async function WarehouseLocationTreePage({
  params,
}: {
  params: Promise<{ id: string }>;
}) {
  const resolvedParams = await params;
  return <WarehouseLocationTreeWorkspace warehouseId={resolvedParams.id} />;
}
