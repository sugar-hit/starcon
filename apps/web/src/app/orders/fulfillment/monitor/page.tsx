import { FulfillmentMonitorWorkspace } from '@/components/orders/fulfillment-monitor-workspace';

/**
 * 9.3.3.2 履约流程监控。
 *
 * <p>蓝图 09 卷：「监控履约流程的异常与超时：预占失败、库存不足、流程卡在某步、
 * 超时未发货，提供重试、切换路径、人工介入入口。」输出是
 * 「异常流程清单 + 处置操作 + 处置记录」。
 *
 * <p>这是一个真实工作区而不是占位页：能力声明、异常检出（四类判据各自独立）、
 * 查证凭据、四类处置动作、处置状态推进、释放预占登记、租户可维护的超时阈值、
 * 人工介入待办全部落到 PostgreSQL 16，加载态 / 空态 / 错误态三态齐全。
 *
 * <p>路由取 {@code /orders/fulfillment/monitor}，与 9.3.3.1 的
 * {@code /orders/fulfillment} **并列**而不是嵌套：那是「编排一次履约」，
 * 本模块是「一堆履约卡住了怎么看、谁来处置」—— 两件事的主语不同，权限与日志也该分开。
 *
 * <p>页面把蓝图的加粗句做成了交互顺序：<strong>先查证、后处置</strong>。
 * 「重试」按钮的可用性由后端的 {@code retryAllowed} 说了算，不允许重试时把
 * {@code retryBlockedReason} 原样显示出来，而不是让操作员点一下撞 409 再猜差什么 ——
 * 猜错的代价是重复发货。
 */
export default function OrdersFulfillmentMonitorPage() {
  return <FulfillmentMonitorWorkspace />;
}
