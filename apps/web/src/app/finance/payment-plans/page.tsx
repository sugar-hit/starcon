import { PaymentPlanWorkspace } from '@/components/finance/payment-plan-workspace';

/**
 * 12.1.2.2 付款计划。
 *
 * <p>蓝图 12-finance.md：「按付款条件（账期、里程碑、到期日）生成付款计划与到期提醒，
 * 支持按供应商与到期窗口批量安排付款。」输出：「付款计划表 + 到期提醒 + 资金需求预测。」
 * 加粗句：「<b>付款计划与资金预测联动</b>：跨境资金常有回款延迟与付款刚性，错配会导致现金流断裂。」
 *
 * <p>这是一个**真实工作区**：付款条件与规则（账期规则 / 里程碑规则）+ 批量安排付款 +
 * 生成付款计划 + 付款计划表（真分页 / 真筛选 / 真排序）+ 付款批次 + 资金需求预测 +
 * 到期提醒（判定 / 确认 / 忽略），加载 / 空 / 错误三态齐全，**没有占位数据**。
 *
 * <p>路由取 {@code /finance/payment-plans} —— 与 12.1.2.1 的 {@code /finance/payables}
 * **并列**而非嵌套：两个模块的页面各自独立，前端一眼能分清「单据」与「计划」。
 *
 * <p>页面<b>刻意没有</b>「执行付款」「标记已付」两个入口：付款属 12.2.2.1（未交付），
 * 能力声明会如实把它们报成 {@code available=false} 并写明归属模块；
 * 同样<b>没有</b>任何「已通知」的显示：提醒推送属 1.3（未交付）。
 */
export default function FinancePaymentPlansPage() {
  return <PaymentPlanWorkspace />;
}
