import { FulfillmentRequestWorkspace } from '@/components/orders/fulfillment-request-workspace';

/**
 * 9.3.3.1 履约请求。
 *
 * <p>蓝图 09 卷：「编排一次履约：锁定预占 → 生成出库单 → 编排作业 → 跟踪完成；
 * 持久化流程状态，支持断点恢复与超时重试。」输出是
 * 「履约请求 + 出库单 + 流程状态记录 + 事件（下游消费）」。
 *
 * <p>这是一个真实工作区而不是占位页：建单、四步推进、下游指令登记与回执、
 * 编排作业、挂起 / 重试 / 取消、超时扫描与超时记录清单全部落到 PostgreSQL 16，
 * 加载态 / 空态 / 错误态三态齐全，版本冲突单独处理。
 *
 * <p>路由取 {@code /orders/fulfillment}（台账里「待发货订单」那一条，本车道专属），
 * 与 9.3.2.2 的 {@code /fulfillment/routing}、9.3.2.1 的 {@code /fulfillment/allocation}、
 * 9.3.1.2 的 {@code /fulfillment/consolidation} 并列 —— 合到什么单元、走哪条路径、
 * 从哪个仓发、怎么推进是四件事，权限与日志分得开。
 *
 * <p>页面第一屏就把「本模块**不预占上游库存**」说清楚：预占与出库单都只是
 * **本服务内登记的下游指令**，缺省状态是「待回执」。这是 9.3.2.1（V116）
 * 踩过并写进证据的坑 —— 两边都以为占了同一批货。
 */
export default function OrdersFulfillmentPage() {
  return <FulfillmentRequestWorkspace />;
}
