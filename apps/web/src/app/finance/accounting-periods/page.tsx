import { AccountingPeriodWorkspace } from '@/components/finance/accounting-period-workspace';

/**
 * 12.5.1.2 会计期间。
 *
 * <p>蓝图 12-finance.md：「管理会计期间（自然月或 4-4-5 fiscal 周期）：期间状态（开放/关闭）、
 * 关账与重开、期间内凭证限制。」输入：「会计日历配置；关账/重开操作；审批。」输出：「期间状态；关账记录。」
 * 关系：「上游 = 会计日历；下游 = 12.5.2.1 凭证（已关闭期间禁止过账）。**重开已关期间是高风险操作**，
 * 必须审批留痕。」
 *
 * <p>这是一个**真实工作区**：会计日历（自然月 / 4-4-5）+ 按日历生成一个财年的期间 +
 * 期间列表（真分页 + 真筛选 + 状态汇总）+ 关账（必须写原因）+ 重开（走 17 审批，四种结局如实显示）+
 * 期间详情（关账记录 + 审批链路），加载 / 空 / 错误三态齐全，**没有占位数据**。
 *
 * <p>路由取 {@code /finance/accounting-periods} —— 与同服务的其它端点（{@code /finance/settlement}、
 * {@code /finance/transactions}、{@code /finance/vouchers} …）**并列**而非嵌套，首 token 也刻意不同。
 *
 * <p>页面<b>刻意没有</b>两个入口：「关账检查清单」（12.5.1.3 未交付）与「批量关账」
 * （本模块没有批量端点）—— 能力声明会如实报出 {@code available=false} 并写明归属模块。
 */
export default function AccountingPeriodPage() {
  return <AccountingPeriodWorkspace />;
}
