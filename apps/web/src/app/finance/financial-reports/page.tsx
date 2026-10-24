import { FinancialReportWorkspace } from '@/components/finance/financial-report-workspace';

/**
 * 12.5.2.2 财务报表。
 *
 * <p>蓝图 12-finance.md：「<b>生成资产负债表、利润表、现金流量表、科目余额表、明细账</b>；支持期间对比、导出、钻取到凭证。」输出：「<b>报表 + 钻取路径 + 导出文件</b>。」关系：「<b>报表数字必须能钻到凭证</b>，"报表与明细对不上"是财务系统的致命问题。」
 *
 * <p>这是一个**真实工作区**：五张报表任选 + 期间范围（本期 / 对比期）+ 币种分组
 * + 机器自检（借贷平衡 / 会计恒等式）+ 每行钻取到真实凭证 + 导出 CSV / JSON（真内容）
 * + 报表模板维护，加载 / 空 / 错误三态齐全，**没有占位数据**。
 *
 * <p>路由取 {@code /finance/financial-reports} —— 与 12.5.2.1 的 {@code /finance/vouchers}、
 * 12.4.2.1 的 {@code /finance/cost-collections} **并列**而非嵌套：
 * 那是别的模块的页面（别的命名空间、别的端点）。
 *
 * <p>⚠️ 本页**刻意没有**在 {@code packages/ui/src/lib/task-pages.ts} 登记导航 ——
 * 那个文件是四条车道并发修改的共享文件，并入时必然冲突，留给调度者统一处理。
 */
export default function FinancialReportsPage() {
  return <FinancialReportWorkspace />;
}
