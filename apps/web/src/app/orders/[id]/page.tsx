import { OrderOverviewWorkspace } from '@/components/orders/order-overview-workspace';

/**
 * 9.1.1.2 订单详情（控制塔）路由。
 *
 * <p>蓝图：「<b>详情页是订单的『控制塔』</b>：客服、采购、财务都从这一个页面理解
 * 这笔订单为什么长这样。」路由按蓝图挂在 `/orders/[id]`。
 *
 * <p><b>注意这里是详情页，不是 tab</b>：`/orders` 已是 9.1.1.1 建的 tab 壳
 * （9.1.1 订单列表 / 9.1.2 手工订单）。子路由 `/orders/[id]` 不与那个 tab 壳冲突 ——
 * `page.tsx` 只匹配 `/orders` 本身，`[id]/page.tsx` 只匹配 `/orders/<某段>`。
 */
export default async function OrderDetailPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  return <OrderOverviewWorkspace orderId={id} />;
}
