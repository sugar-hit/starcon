import { DropshipConfigWorkspace } from '@/components/orders/dropship-config-workspace';

/**
 * 9.6.1.1 代发模式配置。
 *
 * <p>蓝图 09 卷：「配置店铺/SKU 是否走代发（一件代发），以及代发供应商、结算方式
 * （下单/确认收货后结算、差价抽成）、可售校验规则；区分『自营发货』与『供应商直发』
 * 两种履约路径。纯代理代发不占用自有仓库存；其他直发模式的货权与结算规则须单独确认。」
 * 输出是「代发模式生效配置」与「代发可售标记」。
 *
 * <p>这是一个真实工作区而不是占位页：店铺 / 供应商报价 / 供应商可供货量投影、
 * 代发配置、状态推进、SKU 明细行与可售标记全部落到 PostgreSQL 16，
 * 加载态 / 空态 / 错误态三态齐全。
 *
 * <p>路由取 {@code /orders/fulfillment/dropship}，与 9.3.2.2 的
 * {@code /orders/fulfillment/routing}、9.3.2.1 的 {@code /orders/fulfillment/allocation}
 * 并列 —— 三者是同一条履约链上相邻但不同的三环（选履约路径 / 选发货仓 / 决定是否走我方仓）。
 *
 * <p>刻意**不占用** {@code /orders/fulfillment} 本身：那个路由在 task-pages 里
 * 已登记给 9.3.3.1 待发货订单（另一条车道），两条车道各写一个 page.tsx 会互相覆盖。
 * 同理刻意**不改** {@code /orders}、{@code /orders/fulfillment/routing} 与
 * {@code /orders/fulfillment/allocation}。
 */
export default function OrdersFulfillmentDropshipPage() {
  return <DropshipConfigWorkspace />;
}
