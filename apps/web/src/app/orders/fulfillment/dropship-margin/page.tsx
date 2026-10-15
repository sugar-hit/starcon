import { DropshipMarginWorkspace } from '@/components/orders/dropship-margin-workspace';

/**
 * 9.6.2.1 代发差价与对账。
 *
 * <p>蓝图 09 卷：「管理代发业务的成本与结算：代发采购成本（供应商结算价 + 供应商运费）、
 * 我方售价、差价毛利；按周期与供应商对账，识别亏损代发单（售价低于代发成本+平台费）
 * 并预警。输出：代发差价与毛利报表（按店铺/供应商/SKU）；供应商对账单（→ 12 应付）；
 * 亏损单预警。」
 *
 * <p>这是一个真实工作区而不是占位页：亏损预警工作台、差价毛利明细（构成项可复算）、
 * 按币种分组的毛利报表、供应商对账与应付待办队列、能力缺口声明全部落到 PostgreSQL 16，
 * 加载态 / 空态 / 错误态三态齐全。
 *
 * <p>路由取 {@code /orders/fulfillment/dropship-margin}，与 9.6.1.1 的
 * {@code /orders/fulfillment/dropship}、9.6.1.2 的
 * {@code /orders/fulfillment/dropship-orders}、9.6.1.3 的
 * {@code /orders/fulfillment/dropship-tracking} **并列**而不是嵌套：配置说
 * 「走不走代发」，代发单说「怎么下的」，跟踪说「到哪了、卡在哪」，
 * 本模块说「这一票赚没赚、这个周期该付供应商多少」。
 *
 * <p>刻意**不占用** {@code /orders/fulfillment}（已登记给 9.3.3.1 待发货订单）
 * 以及上面三个代发路由。
 */
export default function OrdersFulfillmentDropshipMarginPage() {
  return <DropshipMarginWorkspace />;
}
