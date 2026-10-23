import { VoucherWorkspace } from '@/components/finance/voucher-workspace';

/**
 * 12.5.2.1 凭证管理。
 *
 * <p>蓝图 12-finance.md：「<b>生成与管理会计凭证</b>：由业务事件自动生成或手工录入，支持过账、校验（至少两行、金额有效、期间开放、借贷平衡）与红冲；已过账凭证不得反过账后改写，导出标准凭证文件。」关系：「<b>过账后不可改行，只能红冲</b>；过账事务要锁凭证头并在数据库层防护。」
 *
 * <p>这是一个**真实工作区**：会计期间 + 业务事件映射 + 凭证列表（真分页 / 真筛选 / 按币种分开的汇总）
 * + 手工录入 + 自动生成 + 详情（分录行 / 审计留痕）+ 过账 / 红冲 / 作废 + 导出，
 * 加载 / 空 / 错误三态齐全，**没有占位数据**。
 *
 * <p>路由取 {@code /finance/vouchers} —— 与 12.2.2.2 的 {@code /finance/payment-executions}、
 * 12.4.2.1 的 {@code /finance/cost-collections} **并列**而非嵌套：
 * 那些是别的模块的页面（别的命名空间、别的端点）。
 *
 * <p>⚠️ 本页**刻意没有**在 {@code packages/ui/src/lib/task-pages.ts} 登记导航 ——
 * 那个文件是四条车道并发修改的共享文件，并入时必然冲突，留给调度者统一处理。
 */
export default function VouchersPage() {
  return <VoucherWorkspace />;
}
