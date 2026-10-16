import { OrderConsolidationWorkspace } from '@/components/orders/order-consolidation-workspace';

/**
 * 9.3.1.2 合单。
 *
 * <p>蓝图 09 卷：「把同一买家的多笔订单合并成一个包裹发货，降低运费与包材成本；
 * 同时识别跨店铺同买家订单（需分别回传平台）。」输出是
 * 「合并履约请求 + 各原订单的关联记录」。
 *
 * <p>这是一个真实工作区而不是占位页：合单规则、候选检索（真 SQL）、合并落库、
 * 跨店铺识别、逐平台订单的物流号回传全部落到 PostgreSQL 16，
 * 加载态 / 空态 / 错误态三态齐全。
 *
 * <p>路由取 {@code /orders/fulfillment/consolidation}，落在蓝图 09 的 L3 锚点
 * {@code /orders/fulfillment} 之下，与 9.3.2.2 的 {@code /fulfillment/routing}、
 * 9.3.2.1 的 {@code /fulfillment/allocation} 并列 —— 合、合到什么仓、走什么履约路径
 * 是三件事，权限与日志分得开。
 *
 * <p>刻意**不占用** {@code /orders/fulfillment} 本身（已登记给 9.3.3.1 待发货订单，
 * 另一条车道），也不动 {@code /orders}（9.1 订单列表车道）。
 */
export default function OrdersFulfillmentConsolidationPage() {
  return <OrderConsolidationWorkspace />;
}
