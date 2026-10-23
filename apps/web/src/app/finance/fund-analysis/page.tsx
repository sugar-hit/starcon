import { FundAnalysisWorkspace } from '@/components/finance/fund-analysis-workspace';

/**
 * 12.7.2.1 资金分析。
 *
 * <p>蓝图 12-finance.md：「资金分析：现金流入流出趋势、各币种资金分布与汇率敞口、资金周转天数、付款集中度与流动性预警。」输入：「资金流水；应收应付账期；汇率数据。」输出：「资金报表 + 汇率敞口测算 + 流动性预警。」
 * 加粗句：「<b>汇率敞口是跨境特有风险</b>：大量外币存货 + 本币负债的组合下，汇率波动直接影响利润。」
 *
 * <p>这是一个**真实工作区**：六项能力各自一个区块（现金流入流出趋势 / 各币种资金分布 /
 * 汇率敞口测算 / 资金周转天数 / 付款集中度 / 流动性预警）+ 流动性预警阈值配置，
 * 加载 / 空 / 错误三态齐全，**没有占位数据**。
 *
 * <p>路由取 {@code /finance/fund-analysis} —— 与 12.1.1.1 的 {@code /finance}（应收单管理）、
 * 12.1.1.2 的 {@code /finance/aging}（应收账龄）**并列**而非嵌套：
 * 那些是别的模块的页面（别的命名空间、别的端点）。
 *
 * <p>⚠️ 本页**刻意没有**在 {@code packages/ui/src/lib/task-pages.ts} 登记导航 ——
 * 那个文件是四条车道并发修改的共享文件，并入时必然冲突，留给调度者统一处理。
 */
export default function FundAnalysisPage() {
  return <FundAnalysisWorkspace />;
}
