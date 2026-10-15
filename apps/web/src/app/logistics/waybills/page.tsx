import { WaybillWorkspace } from '@/components/logistics/waybill-workspace';

/**
 * 10.2.1.2 运单申请。
 *
 * <p>蓝图卷 10：「向承运商申请运单号：传入收寄信息、包裹明细、申报信息，
 * 支持批量申请、失败重试、接口限流控制。」输入是「待发货包裹；渠道；
 * 申报信息（3.7 报关属性）；idempotency_key」，输出是「运单号 + 面单数据；失败原因」。
 *
 * <p>这是一个真实工作区而不是占位页：承运商登记、限流队列（可见的队列车位）、
 * 申请清单（真分页 / 真筛选）、逐条重试与作废全部落到 PostgreSQL 16，
 * 加载态 / 空态 / 错误态三态齐全。
 *
 * <p>路由取 {@code /logistics/waybills}，与蓝图给 10.2 运单管理规划的
 * {@code /logistics/shipments} 同属一个功能区，但**刻意不占用那个路径** ——
 * 10.2.1.1 运单列表（未开发）会把 {@code /shipments} 长成完整列表页，
 * 两条车道各写一个 page.tsx 会互相覆盖。本模块是「去申请」这一个动作，
 * 路径说清是申请而不是整个运单域。
 *
 * <p>数据来源在界面上如实写明：待发货包裹只从 9.4.1.1 的
 * {@code shipment_pending_parcel} 读（同服务同库，V124 唯一写入者是 9.4.1.1）；
 * 申报信息只从 catalog 3.7.1.1 的授权只读接口读；面单模板只从 warehouse 8.3.4.2 的
 * 授权只读接口读 —— 跨库视图、跨服务 SQL、跨服务 FK 都被 AGENTS.md 禁止。
 */
export default function LogisticsWaybillsPage() {
  return <WaybillWorkspace />;
}
