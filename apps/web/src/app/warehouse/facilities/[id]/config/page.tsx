import { WarehouseConfigWorkspace } from '@/components/warehouse/warehouse-config-workspace';

/**
 * 8.1.1.3 仓库配置。
 *
 * <p>蓝图：「配置仓库行为参数：是否支持批次/序列号、拣货策略（按订单/按波次/按库位）、
 * 默认复核比例、是否允许拆零发货、工作时段与班次。」以及「配置影响作业方式，
 * 不是纯展示项，修改需记录变更。」
 *
 * <p>路由挂在 8.1.1.1 的仓库档案之下（{@code /warehouse/facilities/[id]/config}）
 * 而不是新开一个 L3：配置是仓库的属性，与蓝图 08 的目录（8.1.1 仓库档案 → 8.1.1.3 配置）
 * 一致。它与 8.1.1.2 详情是同一个 page_resource（identity V69 注册），
 * 所以 identity V104 只补权限码、**不**再插 page_resource ——
 * 插了菜单里会多出一个与「仓库档案」重复的条目。
 *
 * <p>这是一个真实工作区而不是占位页：作业参数（六个字段的整份维护）、工作时段与班次
 * （新增 / 编辑 / 停用，时长与跨夜由服务端算）、下游影响声明（逐条 available +
 * ownerModule + 原因）、变更留痕（真分页 + 按范围与班次筛选，逐字段「从什么 → 什么」），
 * 全部来自 PostgreSQL 16 上真实落库的数据。
 */
export default async function WarehouseConfigPage({ params }: { params: Promise<{ id: string }> }) {
  const resolvedParams = await params;
  return <WarehouseConfigWorkspace warehouseId={resolvedParams.id} />;
}
