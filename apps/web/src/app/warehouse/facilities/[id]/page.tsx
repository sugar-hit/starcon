import { WarehouseDetailWorkspace } from '@/components/warehouse/warehouse-detail-workspace';

/**
 * 8.1.1.2 仓库详情 —— 仓库的控制塔。
 *
 * <p>蓝图：「单仓库全景：基础信息与地址、库位结构、库存分布、作业统计、关联的自发货/中转/
 * FBA 备货策略、作业人员与设备。」「详情页是仓库的控制塔，作业入口与库存入口都在这里。」
 *
 * <p>路由挂在 8.1.1.1 的列表页之下（{@code /warehouse/facilities/[id]}）而不是新开一个 L3：
 * 详情与列表是同一个页面（同一个 page_resource、同一组权限码）。8.1.1.1 交付时把
 * {@code detailUnavailable} 写死为 true 让前端禁用详情入口，8.1.1.2 交付时把它翻过来 ——
 * 那个布尔值就是两条车道之间的交接开关。
 *
 * <p>这是一个真实工作区而不是占位页：基础信息与地址、库存分布（按质量状态 / 货权法人 /
 * 库位类型三个切面）、库位结构与库位清单（真分页 + 排序白名单）、作业统计、三条备货策略、
 * 作业人员与设备、各子模块入口，以及基础信息与地址的维护，全部来自 PostgreSQL 16 上
 * 真实落库的聚合查询。
 */
export default function WarehouseDetailPage({ params }: { params: { id: string } }) {
  return <WarehouseDetailWorkspace warehouseId={params.id} />;
}