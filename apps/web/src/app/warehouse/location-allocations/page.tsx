import { WarehouseLocationAllocationWorkspace } from '@/components/warehouse/warehouse-location-allocation-workspace';

/**
 * 8.1.2.2 库位自动分配。
 *
 * <p>蓝图：「按规则为入库商品推荐库位：同品类聚类、最短上架路径、容量与承重匹配、
 * 待检/残次专用库位强制规则。输入：入库 SKU 与数量；库位属性与占用；路径距离。
 * 输出：推荐库位列表（带推荐理由）。」以及「**质量状态决定库位类型**：残次品入残次库位是
 * 硬规则，不允许混放。」
 *
 * <p><b>为什么是新路由而不是挂在 8.1.2.1 的库位树之下。</b>库位树是「维护库位档案」
 * （增删改、层级、容量承重），自动分配是「对一批具体的货给出建议」—— 一个是主数据维护，
 * 一个是作业决策，两者的输入、输出与权限含义都不同。挂在库位树下面会让人以为
 * 「改库位」和「给这批货派库位」是同一个动作。
 *
 * <p>路由用 {@code /warehouse/location-allocations} 而不是 {@code /warehouse/locations/...}：
 * 前者与后端端点同形（白名单按路径形状放行，两边错位是最常见的 404 来源），
 * 后者 7.1.x 已经占着（库位下拉参照项）。
 *
 * <p>这是一个真实工作区而不是占位页：候选库位、推荐分与逐条理由、容量承重的三态
 * （够 / 判不了 / 量过但超限不进候选）、硬规则推出的目标库位类型、已选定决策的清单与
 * 释放/撤销，全部来自 PostgreSQL 16 上真实落库的查询与写入。加载 / 空 / 错误三态分开渲染。
 */
export default function WarehouseLocationAllocationPage() {
  return <WarehouseLocationAllocationWorkspace />;
}
