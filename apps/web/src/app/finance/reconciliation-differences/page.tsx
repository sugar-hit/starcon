import { ReconciliationDifferenceWorkspace } from '@/components/finance/reconciliation-difference-workspace';

/**
 * 12.3.2.2 差异处理。
 *
 * <p>蓝图 12-finance.md：「处理对账差异：分类（我方多记/对方多记/时间差/汇率差/手续费遗漏）、
 * 指定责任人、人工判定与调整、闭环跟踪。」输入：「差异明细；处理结论；调整凭证或说明。」
 * 输出：「差异处理记录 + 调整分录 + 闭环状态。」加粗句：「<b>差异分类是改进闭环的输入</b>：
 * 某类差异反复出现说明上游流程有系统性问题。」
 *
 * <p>这是一个**真实工作区**：差异处理队列（真分页 + 真筛选 + 真搜索）+ **按币种分组**的汇总
 * + **分类分布**（改进闭环的输入）+ 单条差异的处理动作（认领 / 改派 / 分类归因 /
 * 登记调整分录 / 判定已调整 / 判定已核销）+ 调整分录清单 + 闭环时间线，
 * 加载 / 空 / 错误三态齐全，**没有占位数据**。
 *
 * <p>路由取 {@code /finance/reconciliation-differences} —— 与 12.3.2.1 对账任务
 * （{@code /finance/reconciliation-tasks}）、12.7.1.2 银行对账
 * （{@code /finance/bank-reconciliation}）、12.1.2.3 供应商对账
 * （{@code /finance/supplier-reconciliation}）**并列**而非嵌套：那些是别的模块的页面
 * （别的命名空间、别的端点、别的写入所有权）。
 *
 * <p>⚠️ 本页**刻意没有**在 {@code packages/ui/src/lib/task-pages.ts} 登记导航 ——
 * 那个文件是多条车道并发修改的共享文件，并入时必然冲突，留给调度者统一处理。
 */
export default function ReconciliationDifferencesPage() {
  return <ReconciliationDifferenceWorkspace />;
}
