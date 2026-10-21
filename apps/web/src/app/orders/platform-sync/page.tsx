import { PlatformOrderSyncWorkspace } from '@/components/platform-order-sync/platform-order-sync-workspace';

/**
 * 9.1.2.1 平台订单同步。
 *
 * <p>蓝图 09 卷：「接收并落库平台订单（webhook 实时 + 轮询补拉），做字段解析、状态归一、
 * 金额与税费还原、地址快照，形成统一订单模型」「**同一外部订单号在同一来源范围内唯一**，
 * 重复事件走 inbox 去重，绝不产生第二张订单」。
 *
 * <p>这是一个真实工作区而不是占位页：同步批次、平台单、组件展开、异常明细、手工重放
 * 全部落到 PostgreSQL 16，加载态 / 空态 / 错误态三态齐全。
 *
 * <p>路由取 {@code /orders/platform-sync}，落在蓝图 09 的 L3 锚点 {@code /orders} 之下，
 * 与 9.1.2.2 手工订单（{@code /orders} 的另一个 tab）并列。
 *
 * <p>刻意**不改动** {@code /orders} 下既有的任何 page.tsx —— 那是 9.1.1.x / 9.1.2.2
 * 已交付模块的页面，不在本车道的修改范围。
 */
export default function OrdersPlatformSyncPage() {
  return <PlatformOrderSyncWorkspace />;
}
