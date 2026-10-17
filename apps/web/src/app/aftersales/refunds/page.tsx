import { RefundStatusWorkspace } from '@/components/aftersales/refund-status-workspace';

/**
 * 11.3.1.2 退款状态。
 *
 * <p>蓝图 11-aftersales-and-returns.md：「跟踪退款执行状态与平台同步状态，处理退款失败、
 * 外部超时（先查证交易再重试，禁止重复支付）、平台退款与系统退款不一致的差异。」
 * 输出句：「<b>退款状态 + 失败原因 + 重试/人工处理建议 + 差异清单</b>。」
 * 加粗句：「<b>外部退款超时不等于失败</b>，重复发起退款会造成实质资损。」
 *
 * <p>**为什么这层有独立页面**：蓝图的 L4 定义是「一个页面**或**一个能力」，本身不强制页面；
 * 但本模块的输出是一张**运营每天要逐条处置的工作面**（待处置优先的列表 + 差异清单 + 逐条建议），
 * 而不是一段被下游消费的声明 —— 15 客服与 9 订单是它的**下游**，不是它的用户界面。
 * 且 `task-pages.ts` 里 `/aftersales/refunds` 这个入口**早就存在**（`implemented: false`），
 * 任何人点「退款与补发」都会落到一个空壳。交付后端而不接这个入口，等于让一个真实可用
 * 的能力继续以「未实现」示人。批次 18 的 11.1.1.1 当初也正是因此把 DomainPlaceholder
 * 换成了真实工作区。
 *
 * <p>这是**真实工作区**：数据全部来自 {@code GET /trade/refunds} 等 11 个真实端点，
 * 加载 / 空 / 错误三态齐全，不是只有字段名的占位页。
 *
 * <p>⚠️ 页面**刻意不提供**「发起退款」的完整申请流程：那是 11.3.1.1 退款申请
 * （可退余额校验 + 审批）的地盘，capabilities 的 {@code refundRequestDelivered=false}
 * 如实报出。这里只有「把退款登记成待发起」的入口，不含余额校验与审批。
 */
export default function AftersalesRefundsPage() {
  return <RefundStatusWorkspace />;
}
