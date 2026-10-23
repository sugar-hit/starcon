import { PaymentExecutionWorkspace } from '@/components/finance/payment-execution-workspace';

/**
 * 12.2.2.2 付款执行。
 *
 * <p>蓝图 12-finance.md：「<b>执行付款：对接支付渠道/银企直连，发送付款指令，跟踪结果，
 * 失败重试与异常处理；处理汇率锁汇。</b>输入：已批付款单；支付渠道配置（密钥引用）；汇率锁定。
 * 输出：<b>付款结果 + 渠道流水号 + 失败原因</b>。关系：上游 = 12.2.2.1。
 * <b>外部支付超时不等于失败</b>，必须先查证再重发——重复付款是严重资损事故。」
 *
 * <p>这是一个**真实工作区**：发起付款执行（选已批付款单 + 选渠道 → 发送）+ 支付渠道配置
 * （凭据只存**引用**）+ 执行单表（真分页 / 真筛选 / 按币种分开汇总）+ 执行单详情
 * （尝试记录 / 汇率锁定 / 付款分摊 / 事件流）+ 状态查证 + 查证后重发，
 * 加载 / 空 / 错误三态齐全，**没有占位数据**。
 *
 * <p>路由取 {@code /finance/payment-executions} —— 与 12.2.2.1 的 {@code /finance/payment-orders}、
 * 12.1.2.2 的 {@code /finance/payment-plans} **并列**而非嵌套：
 * 三个模块的页面各自独立，前端一眼能分清「申请」「计划」「执行」。
 *
 * <p>🔴 页面把「结果待查证」渲染成**独立的第四种语气**（warning，不复用 FAILED 的 danger）：
 * 外部超时不等于失败，标红会让人去点重试 —— 而重复付款是严重资损事故。
 */
export default function FinancePaymentExecutionsPage() {
  return <PaymentExecutionWorkspace />;
}
