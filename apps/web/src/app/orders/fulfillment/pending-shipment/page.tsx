import { PendingShipmentWorkspace } from '@/components/orders/pending-shipment-workspace';

/**
 * 9.4.1.1 待发货。
 *
 * <p>蓝图 09 卷：「待发货订单看板：按仓库、承运渠道、承运商、发货时限分组展示，
 * 支持批量申请运单号、批量打单、批量交接。」输入是「已完成打包的出库单」与
 * 「发货时限要求（平台 SLA）」，输出是「待发货清单 + 批量操作任务」。
 *
 * <p>这是一个真实工作区而不是占位页：分组看板（四个维度都是后端 GROUP BY）、
 * 待发货清单、批量申请运单号 / 批量打单 / 批量交接三张真任务、以及逐条执行结果
 * 全部落到 PostgreSQL 16，加载态 / 空态 / 错误态三态齐全。
 *
 * <p>路由取 {@code /orders/fulfillment/pending-shipment}，与 9.3.2.2 的
 * {@code /orders/fulfillment/routing}、9.3.2.1 的 {@code /orders/fulfillment/allocation}
 * 并列 —— 三者是同一条履约链上相邻的三环（选路径 → 选仓 → 发货），
 * 不是彼此的子路由。
 *
 * <p>刻意**不占用** {@code /orders/fulfillment} 本身：那个路由在 task-pages 里
 * 已登记给 9.3.3.1（另一条车道），两条车道各写一个 page.tsx 会互相覆盖。
 * 同理刻意**不改** {@code /orders} 与另两条 fulfillment 子路由。
 *
 * <p>数据来源在界面上如实写明：待发货清单只从 warehouse 服务 8.3.3.2 的
 * **授权只读接口**同步（跨库视图、跨服务 SQL、跨服务 FK 都被 AGENTS.md 禁止）。
 */
export default function OrdersFulfillmentPendingShipmentPage() {
  return <PendingShipmentWorkspace />;
}