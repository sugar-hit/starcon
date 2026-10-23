import { CostCollectionWorkspace } from '@/components/finance/cost-collection-workspace';

/**
 * 12.4.2.1 费用归集。
 *
 * <p>蓝图 12-finance.md：「归集各类费用到指定维度：按月分摊的平台费/订阅费、按单分摊的物流费、按 SKU 分摊的头程费、按期间分摊的人员与办公费用，<b>保留归集依据</b>。」关系：「<b>归集与分摊要分开</b>：先按规则归集费用池，再按维度分摊，便于核对与调整。」
 *
 * <p>这是一个**真实工作区**：归集规则 + 蓝图标准绑定模板 + 五个来源的归集入口 +
 * 费用池 + 归集明细（带归集依据），加载 / 空 / 错误三态齐全，**没有占位数据**。
 *
 * <p>路由取 {@code /finance/cost-collection} —— 与 12.3.1.2 的 {@code /finance/transactions}、
 * 12.7.2.1 的 {@code /finance/fund-analysis} **并列**而非嵌套：
 * 那些是别的模块的页面（别的命名空间、别的端点）。
 *
 * <p>⚠️ 本页**刻意没有**在 {@code packages/ui/src/lib/task-pages.ts} 登记导航 ——
 * 那个文件是四条车道并发修改的共享文件，并入时必然冲突，留给调度者统一处理。
 */
export default function CostCollectionPage() {
  return <CostCollectionWorkspace />;
}
