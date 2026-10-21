import { AllocationResultWorkspace } from '@/components/inventory/allocation-result-workspace';

/**
 * 7.8.2.1 分摊结果查看。
 *
 * <p>蓝图：「展示某 SKU/仓在各店铺的分摊量、已用、剩余、回写平台的数值，以及回写失败或延迟导致的实际风险敞口。输入：分摊结果；平台回写状态（4.3.2.1）。输出：分摊明细表 + 平台回写一致性核对。」
 *
 * <p>这是一个真实工作区而不是占位页：分摊明细表、平台回写一致性核对、逐轮风险敞口
 * 与回写状态观测都落在 PostgreSQL 上，而不是内存里的假数据。
 *
 * <p>路由说明：L3「7.8 多平台可售量分摊」的路由是 `/inventory/allocation`，
 * 而它已被 7.8.1.1（分配策略配置）占用。本页按 7.8.1.2 的先例取同族兄弟路由
 * `/inventory/allocation-results`；同时在 `/inventory/allocation/results` 保留一个
 * 指向同一工作区的入口，让 L3 路由下也能直达。
 */
export default function AllocationResultPage() {
  return <AllocationResultWorkspace />;
}
