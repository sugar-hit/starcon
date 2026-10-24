import { CostVarianceWorkspace } from '@/components/finance/cost-variance-workspace';

/**
 * 12.4.1.3 成本差异分析。
 *
 * <p>蓝图 12-finance.md：「分析成本差异：实际成本 vs 预算/标准成本、采购价波动、头程成本波动、损耗差异、汇率影响，定位利润波动主因。」关系：「上游 = 12.4.1.2；下游 = 13 分析、6 采购（价格谈判依据）。<b>利润下滑时先看成本差异</b>，比看销售额更能定位问题。」
 *
 * <p>这是一个**真实工作区**：标准成本 / 预算基线维护 + 执行差异分析 +
 * 报表列表 + 按币种分组的报表明细（五类差异 + 贡献排名 + 主因 + 异常提示）+
 * 能力声明，加载 / 空 / 错误三态齐全，**没有占位数据**。
 *
 * <p>路由取 {@code /finance/cost-variance} —— 与 12.4.1.2 的 {@code /finance/cost-calculation}、
 * 12.4.1.1 的 {@code /finance/cost-valuation} **并列**而非嵌套：
 * 那些是别的模块的页面（别的命名空间、别的端点）。
 *
 * <p>⚠️ 本页**刻意没有**在 {@code packages/ui/src/lib/task-pages.ts} 登记导航 ——
 * 那个文件是四条车道并发修改的共享文件，并入时必然冲突，留给调度者统一处理。
 */
export default function CostVariancePage() {
  return <CostVarianceWorkspace />;
}
