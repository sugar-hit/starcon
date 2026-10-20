'use client';

import { ListingLifecycleWorkspace } from '@/components/listing-lifecycle/listing-lifecycle-workspace';

/**
 * 生命周期与下架：L3「4.1 商品内容与发布」下的 4.1.1.3。
 *
 * <p>🔴 为什么**独立成页**而不是做成 Listing 列表的一个筛选维度：这是**三条独立通道** ——
 *
 * <ul>
 *   <li><b>4.1.1.1 Listing 列表</b> —— 读列表（页面在 <code>/listings</code>）。
 *   <li><b>4.1.1.2 Listing 详情</b> —— 读全景（页面在 <code>/listings/[id]</code>）。
 *   <li><b>4.1.1.3 生命周期与下架</b> —— <strong>改状态</strong>，并且带一条**回写平台**的
 *       旁路（<code>writeback_batch</code>）。页面在 <code>/listings/lifecycle</code>。
 * </ul>
 *
 * <p>🔴 混在一页里会出现「这个下架按钮到底改的是列表还是平台」这种问题 ——
 * 读与写的后果完全不同，路由上也应该分开。
 *
 * <p>🔴 本模块**不重算**后端的差异判定与状态机：那是服务端算好后下发的结论，
 * 页面原样显示（判定口径是业务规则，前端再实现一遍必然随版本漂移）。
 */
export default function ListingLifecyclePage() {
  return (
    <div className="space-y-4">
      <ListingLifecycleWorkspace />
    </div>
  );
}
