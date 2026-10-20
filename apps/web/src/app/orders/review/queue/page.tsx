import { OrderPendingReviewWorkspace } from '@/components/orders/order-pending-review-workspace';

/**
 * 9.2.1.1 待审队列。
 *
 * <p>蓝图：「展示待审订单，按风险与时效排序，支持批量通过/驳回/挂起，
 * 显示每单命中的规则与风险信号。」
 * 蓝图加粗句：「审单是发货前的最后人工闸门，批量通过必须基于明确的规则，而不是默认全过。」
 *
 * <p>路由取 {@code /orders/review/queue}：落在蓝图 09 的 L3 锚点 {@code /orders/review} 之下，
 * 与 9.2.1.2 审单规则（{@code /orders/review/rules}）、9.2.1.3 异常拦截
 * （{@code /orders/review/intercept}）并列 —— 「排队给人看并推进结论」「配规则」
 * 「判异常」是三件事，路由与权限都该分得开。
 *
 * <p>同一份工作区另有一个别名路由 {@code /orders/pending-review}（批次任务书里给的路径），
 * 两个 URL 渲染的是**同一个**工作区，不存在两套实现。
 */
export default function OrderPendingReviewPage() {
  return <OrderPendingReviewWorkspace />;
}
