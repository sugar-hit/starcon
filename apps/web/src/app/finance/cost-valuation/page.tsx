import { CostValuationWorkspace } from '@/components/finance/cost-valuation-workspace';

/**
 * 12.4.1.1 计价方法配置。
 *
 * <p>蓝图 12-finance.md：「配置成本计算方法（先进先出/移动加权平均/批次成本/指定成本法），<b>按法人或品类设置</b>，<b>确定生效时间</b>。」关系：「<b>系统提供选项与试算，不替用户决定政策</b>；<b>切换方法需重算并留版本对比</b>。」
 *
 * <p>这是一个**真实工作区**：四个计价方法选项（连同口径说明）+ 计价方法配置（版本链）+
 * 版本对比 + 指定成本单价 + 试算对比，加载 / 空 / 错误三态齐全，**没有占位数据**。
 *
 * <p>路由取 {@code /finance/cost-valuation} —— 与 12.4.2.1 的 {@code /finance/cost-collection}、
 * 12.3.1.3 的 {@code /finance/deferred-settlement} **并列**而非嵌套：
 * 那些是别的模块的页面（别的命名空间、别的端点）。
 *
 * <p>⚠️ 本页**刻意没有**在 {@code packages/ui/src/lib/task-pages.ts} 登记导航 ——
 * 那个文件是四条车道并发修改的共享文件，并入时必然冲突，留给调度者统一处理。
 */
export default function CostValuationPage() {
  return <CostValuationWorkspace />;
}
