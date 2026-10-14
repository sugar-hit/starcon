import { DropshipTrackingWorkspace } from '@/components/orders/dropship-tracking-workspace';

/**
 * 9.6.1.3 代发履约跟踪与异常。
 *
 * <p>蓝图 09 卷：「跟踪代发订单的供应商发货与妥投状态，同步供应商运单号回传平台，
 * 处理代发异常（延迟、丢件、供应商跑单），并把『我方』与『供应商』两段物流在订单上
 * 分开呈现。输出：代发履约状态；异常清单 + 处置（催供应商/改自营发货/退款）。」
 *
 * <p>这是一个真实工作区而不是占位页：能力声明、检出策略、两段物流跟踪、
 * 三类异常清单与判据、三类处置入口、待回传/待处置任务队列全部落到 PostgreSQL 16，
 * 加载态 / 空态 / 错误态三态齐全。
 *
 * <p>路由取 {@code /orders/fulfillment/dropship-tracking}，与 9.6.1.1 的
 * {@code /orders/fulfillment/dropship}、9.6.1.2 的
 * {@code /orders/fulfillment/dropship-orders} **并列**而不是嵌套：配置说
 * 「这个 SKU 走不走代发」，代发单说「这一票怎么下的」，本模块说「这一票现在到哪了、
 * 卡在哪、怎么处置」。
 *
 * <p>刻意**不占用** {@code /orders/fulfillment/dropship}、{@code /orders/fulfillment/dropship-orders}、
 * {@code /orders/fulfillment}（已登记给 9.3.3.1 待发货订单）、
 * 以及 {@code /orders/fulfillment/routing} 与 {@code /orders/fulfillment/allocation}。
 */
export default function OrdersFulfillmentDropshipTrackingPage() {
  return <DropshipTrackingWorkspace />;
}