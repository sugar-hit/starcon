import { CostAllocationWorkspace } from '@/components/finance/cost-allocation-workspace';

/**
 * 12.4.2.2 分摊规则。
 *
 * <p>蓝图 12-finance.md：「配置费用分摊规则：分摊维度（SKU/SPU/店铺/订单/类目）、分摊基数（数量/金额/重量/体积/等额）、生效区间、分摊顺序与舍入差处理。」输入：「规则配置；待分摊费用池。」输出：「分摊结果（保留舍入差额到指定对象）+ 分摊明细。」关系：「上游 = 12.4.2.1；下游 = 13 利润（SKU 利润）。<b>分摊结果之和必须等于被分摊总额</b>（含舍入差处理），否则利润报表总额对不上 —— 这是财务最不能接受的错误。」
 *
 * <p>这是一个**真实工作区**：分摊规则 + 待分摊费用池 + 采集分摊对象 + 执行分摊 +
 * 分摊批次与逐对象明细，加载 / 空 / 错误三态齐全，**没有占位数据**。
 *
 * <p>路由取 {@code /finance/cost-allocation} —— 与 12.4.2.1 的 {@code /finance/cost-collection}、
 * 12.3.1.2 的 {@code /finance/transactions} **并列**而非嵌套：
 * 那些是别的模块的页面（别的命名空间、别的端点）。
 * 本模块与 12.4.2.1 的边界正是「归集 ≠ 分摊」，页面路径也不混在一起。
 *
 * <p>⚠️ 本页**刻意没有**在 {@code packages/ui/src/lib/task-pages.ts} 登记导航 ——
 * 那个文件是多条车道并发修改的共享文件，并入时必然冲突，留给调度者统一处理。
 */
export default function CostAllocationPage() {
  return <CostAllocationWorkspace />;
}
