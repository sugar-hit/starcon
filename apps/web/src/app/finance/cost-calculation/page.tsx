import { CostCalculationWorkspace } from '@/components/finance/cost-calculation-workspace';

/**
 * 12.4.1.2 成本计算执行。
 *
 * <p>蓝图 12-finance.md：「执行成本计算：对销售出库、发货、平台仓转出计算应结转成本，生成成本层与成本分录；支持按期间批量重算与差异对比。」关系：「上游 = 7 库存流水、12.4.1.1；下游 = 13 利润、12.5.2 凭证。<b>数量账在 warehouse、价值账在 finance</b>——这是两个服务的职责划分，重算不能写库存。」
 *
 * <p>这是一个**真实工作区**：执行成本计算 + 计算批次列表 + 批次明细（成本层 + 成本分录）+
 * 重算 + 差异对比，加载 / 空 / 错误三态齐全，**没有占位数据**。
 *
 * <p>路由取 {@code /finance/cost-calculation} —— 与 12.4.1.1 的 {@code /finance/cost-valuation}、
 * 12.4.2.1 的 {@code /finance/cost-collection} **并列**而非嵌套：
 * 那些是别的模块的页面（别的命名空间、别的端点）。
 *
 * <p>⚠️ 本页**刻意没有**在 {@code packages/ui/src/lib/task-pages.ts} 登记导航 ——
 * 那个文件是四条车道并发修改的共享文件，并入时必然冲突，留给调度者统一处理。
 */
export default function CostCalculationPage() {
  return <CostCalculationWorkspace />;
}
