import { ReconciliationTaskWorkspace } from '@/components/finance/reconciliation-task-workspace';

/**
 * 12.3.2.1 对账任务。
 *
 * <p>蓝图 12-finance.md：「发起与执行对账任务：选择对账范围（店铺 + 周期 + 账户）、运行自动匹配、
 * 输出匹配结果与差异。」输入：「对账范围；我方账数据（结算、应收、应付、银行流水）；平台账单。」
 * 输出：「匹配结果（一对一、一对多、多对一）+ 差异清单。」加粗句：「<b>不允许『金额相同即匹配』</b>
 * ——金额相同只是候选，必须结合订单号、日期、类型。」
 *
 * <p>这是一个**真实工作区**：对账任务列表（真分页 + 真筛选 + 真搜索）+ 建任务 / 改范围
 * （店铺 + 周期 + 账户 + 我方账来源）+ 跑自动匹配 + 按币种分组的汇总 + 匹配结果（每行带
 * 「比对了哪几个维度」）+ 差异清单 + 事件流，加载 / 空 / 错误三态齐全，**没有占位数据**。
 *
 * <p>路由取 {@code /finance/reconciliation-tasks} —— 与 12.3.1.1 结算单（{@code /finance/settlement}）、
 * 12.7.1.2 银行对账（{@code /finance/bank-reconciliation}）、12.1.2.3 供应商对账
 * （{@code /finance/supplier-reconciliation}）**并列**而非嵌套：那些是别的模块的页面
 * （别的命名空间、别的端点、别的写入所有权）。
 *
 * <p>⚠️ 本页**刻意没有**在 {@code packages/ui/src/lib/task-pages.ts} 登记导航 ——
 * 那个文件是四条车道并发修改的共享文件，并入时必然冲突，留给调度者统一处理。
 */
export default function ReconciliationTasksPage() {
  return <ReconciliationTaskWorkspace />;
}
