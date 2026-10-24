import { BankReconciliationWorkspace } from '@/components/finance/bank-reconciliation-workspace';

/**
 * 12.7.1.2 银行对账。
 *
 * <p>蓝图 12-finance.md：「导入银行/支付平台流水与系统记录自动匹配，输出未匹配项，支持人工认领与说明，生成对账报告。」输入：「流水文件（导入）；系统收支记录；匹配规则。」输出：「匹配结果 + 未匹配清单 + 对账报告。」加粗句：「<b>未匹配项要能认领到具体单据</b>，否则余额永远平不了——这是财务日常最高频的工作。」
 *
 * <p>这是一个**真实工作区**：对账批次列表（真分页 + 真筛选）+ 建批次 + 流水导入
 * （CSV / JSON 真解析，逐行告警）+ 自动匹配 + 匹配结果 / 未匹配清单 / 认领举证
 * + 余额调节表式对账报告，加载 / 空 / 错误三态齐全，**没有占位数据**。
 *
 * <p>路由取 {@code /finance/bank-reconciliation} —— 与同 L3 的 12.7.1.1 账户管理
 * （{@code /finance/fund-accounts}）、12.7.2.1 资金分析（{@code /finance/fund-analysis}）
 * **并列**而非嵌套：那些是别的模块的页面（别的命名空间、别的端点）。
 *
 * <p>⚠️ 本页**刻意没有**在 {@code packages/ui/src/lib/task-pages.ts} 登记导航 ——
 * 那个文件是四条车道并发修改的共享文件，并入时必然冲突，留给调度者统一处理。
 */
export default function BankReconciliationPage() {
  return <BankReconciliationWorkspace />;
}
