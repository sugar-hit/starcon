import { OrderPendingReviewWorkspace } from '@/components/orders/order-pending-review-workspace';

/**
 * 9.2.1.1 待审队列 —— **别名路由**。
 *
 * <p>批次任务书里给的前端路径是 {@code /orders/pending-review}，本文件就是那个路径。
 * 它渲染的是与 {@code /orders/review/queue} **完全相同**的工作区组件，
 * 不存在两套实现 —— 蓝图 09 的 L3 锚点是 {@code /orders/review}，
 * 而两个兄弟模块（9.2.1.2 审单规则、9.2.1.3 异常拦截）分别在
 * {@code /orders/review/rules} 与 {@code /orders/review/intercept}，
 * 所以 {@code /orders/review/queue} 是**规范路径**，本文件只是别名。
 */
export default function OrderPendingReviewAliasPage() {
  return <OrderPendingReviewWorkspace />;
}
