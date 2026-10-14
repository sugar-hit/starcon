import { OrderChangeCancelWorkspace } from '@/components/orders/order-change-cancel-workspace';

/**
 * 9.1.1.3 订单变更与取消。
 *
 * <p>蓝图 09 卷：「处理订单变更与取消：地址修改、数量调整（需平台允许）、
 * <b>取消未发货数量（先确认仓库停止作业并释放占用；已发货数量转售后）</b>，
 * 并把变更同步回平台。」输出是「取消申请 / 处理中 / 已确认 / 拒绝 / 待核验状态 +
 * 仓库停止作业及释放结果 + 平台回写任务」。
 *
 * <p>这是真实工作区而不是占位页：取消单、逐行可取消量、仓库两段任务与回执、
 * 平台回写任务、事件时间线全部落到 PostgreSQL 16，
 * 加载态 / 空态 / 错误态三态齐全。
 *
 * <p>路由取 {@code /orders/change/cancellations/[id]}：刻意**不占用**
 * {@code /orders/[id]}（9.1.1.2 订单详情已交付）、{@code /orders/fulfillment/allocation}
 * （9.3.2.1）与 {@code /orders/fulfillment/routing}（9.3.2.2），
 * 也刻意**不占用** {@code /orders/change} 本身 —— 那个路由留给本模块的列表页，
 * 两条车道各写一个 page.tsx 会互相覆盖。
 */
export default async function OrderChangeCancellationPage({
  params,
}: {
  params: Promise<{ id: string }>;
}) {
  const { id } = await params;
  return <OrderChangeCancelWorkspace cancellationId={id} />;
}
