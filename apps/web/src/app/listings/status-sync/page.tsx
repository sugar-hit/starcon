'use client';

import { ListingStatusSyncWorkspace } from '@/components/listing-status-sync/listing-status-sync-workspace';

/**
 * 平台状态回流：L3「4.3.2 回写与回流」下的 4.3.2.2。
 *
 * <p>这个 L3 下有两个模块：
 *
 * <ul>
 *   <li><b>4.3.2.1 内容回写</b> —— 把内部已发布的商品内容变更推送到平台（改标题、改图、改描述、
 *       改 A+），支持按变更类型触发或批量触发，并记录回写前后快照。<strong>已交付</strong>，
 *       页面在 <code>/listings/content-writeback</code>。
 *   <li><b>4.3.2.2 平台状态回流</b> —— 接收并展示平台侧的商品状态变化（审核通过 / 驳回 / 下架 /
 *       限流 / 被抑制 / 类目审核 / 价格异常通知），并支持一键处理（按驳回原因批量修正重发）。
 *       <strong>就是本页</strong>。
 * </ul>
 *
 * <p>🔴 为什么**独立成页**而不是做成列表的一个筛选维度：这是**两条独立通道** ——
 * 4.3.2.1 是「把内部变更**推出去**」，本模块是「把平台状态**收回来**」。
 * 两条通道的状态机、限流账与重试互不干涉，混在一页里就会出现「重试」到底重试哪边的问题。
 * 页面路由取 <code>/listings/status-sync</code>（L3「4.3.2」的第二个模块）。
 *
 * <p>🔴 本模块**不重算**后端的冲突判定与告警：那是服务端算好后下发的结论，
 * 页面原样显示（判定口径是业务规则，前端再实现一遍必然随版本漂移）。
 */
export default function ListingStatusSyncPage() {
  return (
    <div className="space-y-4">
      <ListingStatusSyncWorkspace />
    </div>
  );
}
