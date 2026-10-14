import { DropshipOrderWorkspace } from '@/components/orders/dropship-order-workspace';

/**
 * 9.6.1.2 代发单与供应商下单。
 *
 * <p>蓝图 09 卷：「买家下单后，自动/手工向 1688 等供应商代发平台下单，传递地址与商品信息，
 * 回填供应商单号与代发成本，计算我方差价毛利。输出：代发单（供应商、单号、发货状态）；
 * 差价成本；差异告警（供应商无货/涨价/失败）。」
 *
 * <p>这是一个真实工作区而不是占位页：能力声明、代发单列表与差价、供应商回执录入、
 * 发货登记、差异告警工作台与处置、供应商代发接口登记全部落到 PostgreSQL 16，
 * 加载态 / 空态 / 错误态三态齐全。
 *
 * <p>路由取 {@code /orders/fulfillment/dropship-orders}，与 9.6.1.1 的
 * {@code /orders/fulfillment/dropship} **并列**而不是嵌套：配置说「这个 SKU 走不走代发、
 * 供应商是谁」，代发单说「这一票具体怎么下的、供应商回了多少钱、差价还正不正」。
 *
 * <p>刻意**不占用** {@code /orders/fulfillment/dropship}（9.6.1.1 已交付）、
 * {@code /orders/fulfillment}（task-pages 已登记给 9.3.3.1 待发货订单）、
 * 以及 {@code /orders/fulfillment/routing} 与 {@code /orders/fulfillment/allocation}。
 */
export default function OrdersFulfillmentDropshipOrdersPage() {
  return <DropshipOrderWorkspace />;
}
