import { FulfillmentRoutingWorkspace } from '@/components/orders/fulfillment-routing-workspace';

/**
 * 9.3.2.2 FBA 与自发货选择。
 *
 * <p>蓝图 09 卷：「在平台仓（FBA/MFN）与自有/第三方仓之间选择履约路径：优先用平台仓（买家体验好）、
 * 平台仓缺货时切自发货（需符合平台的多渠道配送要求）、或按成本优先。」输出是
 * 「履约路径决策 + 缺货时切换记录」。
 *
 * <p>这是一个真实工作区而不是占位页：资质登记、路径试算、决策落库、缺货切换记录
 * 与决策详情全部落到 PostgreSQL 16，加载态 / 空态 / 错误态三态齐全。
 *
 * <p>路由取 {@code /orders/fulfillment/routing}，落在蓝图 09 的 L3 锚点 {@code /orders/fulfillment}
 * 之下。刻意**不占用** {@code /orders/fulfillment} 本身：那个路由在 task-pages 里
 * 已登记给 9.3.3.1 待发货订单（另一条车道），两条车道各写一个 page.tsx 会互相覆盖，
 * 而且 nav-routes 的守卫会把「有真实页面却被标成未实现」判为失败。
 * 同理刻意**不改** {@code /orders} —— 它是 9.1 订单列表车道在写的入口。
 */
export default function OrdersFulfillmentRoutingPage() {
  return <FulfillmentRoutingWorkspace />;
}