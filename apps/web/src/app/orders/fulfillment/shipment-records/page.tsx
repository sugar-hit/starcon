import { ShipmentRecordsWorkspace } from '@/components/orders/shipment-records-workspace';

/**
 * 9.4.1.2 发货记录。
 *
 * <p>蓝图 09 卷：「查询已发货记录：包裹、运单号、承运商、发货时间、轨迹状态、
 * 买家可见状态、平台回传状态。」输入是「筛选条件；发货事件」，
 * 输出是「发货记录列表 + 明细」。
 *
 * <p>这是一个真实工作区而不是占位页：绩效口径（迟发率 / 有效追踪率）、
 * 分组看板、发货记录清单与明细、轨迹 / 买家可见 / 平台回传三列的登记入口，
 * 全部落到 PostgreSQL 16，加载态 / 空态 / 错误态三态齐全。
 *
 * <p>路由取 {@code /orders/fulfillment/shipment-records}，与 9.4.1.1 的
 * {@code /orders/fulfillment/pending-shipment} **并列而不是嵌套**：
 * 待发货最远只到「已交接」，且刻意从不产生「已发货」（9.4.1.1 文件头第四节写明：
 * 提前置它会让发货记录与 17 绩效的迟发率算在一个从未发生的时刻上）。
 * 把已发货挂到待发货路由之下，会让人以为「待发货推进一下就成了发货记录」。
 *
 * <p>刻意**不占用** {@code /orders/fulfillment} 本身：那个路由在 task-pages 里
 * 已登记给 9.3.3.1（另一条车道）。
 *
 * <p>数据来源在界面上如实写明：发货记录只从 warehouse 服务 8.3.4.1 已确认交接的
 * **授权只读接口**同步（跨库视图、跨服务 SQL、跨服务 FK 都被 AGENTS.md 禁止）；
 * 轨迹 / 买家可见 / 平台回传三列的**上游均未交付**，界面上显示为带出处的
 * 「未登记」，而不是留空或显示成「正常」。
 */
export default function OrdersFulfillmentShipmentRecordsPage() {
  return <ShipmentRecordsWorkspace />;
}
