import { FundAccountWorkspace } from '@/components/finance/fund-account-workspace';

/**
 * 12.7.1.1 账户管理。
 *
 * <p>蓝图 12-finance.md：「管理资金账户：银行账户、支付平台账户、平台备付金账户，记录币种、账号、余额、开户主体（法人）、账户类型。」输入：「账户定义；余额同步或手工更新；关联法人。」输出：「账户档案 + 余额历史。」加粗句：「<b>账户必须绑定法人</b>，跨法人混用资金账户会导致收入归属错误。」
 *
 * <p>这是一个**真实工作区**：账户档案列表（真分页 + 真筛选）+ 建账户 + 账户详情
 * （含余额历史）+ 余额登记 + 按币种分组的余额汇总，
 * 加载 / 空 / 错误三态齐全，**没有占位数据**。
 *
 * <p>路由取 {@code /finance/fund-accounts} —— 与同 L3 的 12.7.2.1 资金分析
 * （{@code /finance/fund-analysis}）、12.2.1.1 收款单（{@code /finance/receipts}）**并列**而非嵌套：
 * 那些是别的模块的页面（别的命名空间、别的端点）。
 *
 * <p>⚠️ 本页**刻意没有**在 {@code packages/ui/src/lib/task-pages.ts} 登记导航 ——
 * 那个文件是四条车道并发修改的共享文件，并入时必然冲突，留给调度者统一处理。
 */
export default function FundAccountsPage() {
  return <FundAccountWorkspace />;
}
