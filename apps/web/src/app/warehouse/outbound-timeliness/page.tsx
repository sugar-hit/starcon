import { WarehouseOutboundTimelinessWorkspace } from '@/components/warehouse/warehouse-outbound-timeliness-workspace';

/**
 * 8.5.3.1 出库时效。
 *
 * <p>蓝图：「统计出库各环节时效：订单到出库时长、拣货时长、打包时长、交接时长，识别超时环节与瓶颈。
 * 输入：订单时间戳；各作业环节时间戳。输出：时效分布 + 瓶颈环节 + 超时明细。」关系段：
 * 「上游 = 9 订单、8.3 作业记录；下游 = 1 工作台履约健康。时效数据直接对应平台迟发率指标，
 * 超时会导致绩效违规与搜索降权。」
 *
 * <p>路由是**顶层** `/warehouse/outbound-timeliness`（不在 `inventory/` 之下，
 * 也不挂在某张出库单之下）：本模块按<b>时间区间 + 仓库</b>聚合，
 * 挂在单据下会让「这一单有多慢」与「这个仓这段时间有多慢」两件事互相顶替 ——
 * 而后者才是迟发率绩效判断用的口径。
 *
 * <p>与 8.5.2.1 库位利用率（挂在 `/warehouse/facilities/[id]/utilization`）不同：
 * 库位利用率读的是**某个仓的容量**，必须挂在仓上下文里；出库时效是**跨仓可比**的
 * （迟发率本来就按平台/店铺/仓分别考核），所以不给它钉死一个仓。
 *
 * <p>这是一个真实工作区而不是占位页：四环节分布（样本/缺失/覆盖率/平均/P50/P90/最大）、
 * 分桶、瓶颈判定依据、样本完整度、**真分页**的超时明细与逐单时长，
 * 全部来自 PostgreSQL 16 上真实落库的查询；加载 / 空 / 错误三态分开渲染。
 */
export default function WarehouseOutboundTimelinessPage() {
  return <WarehouseOutboundTimelinessWorkspace />;
}
