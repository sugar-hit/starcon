'use client';

import { ContentWritebackWorkspace } from '@/components/listing-content-writeback/content-writeback-workspace';

/**
 * 内容回写：L3「4.3.2 回写与回流」下的 4.3.2.1。
 *
 * <p>这个 L3 下有两个模块：
 *
 * <ul>
 *   <li><b>4.3.2.1 内容回写</b> —— 把内部已发布的商品内容变更推送到平台（改标题、改图、改描述、
 *       改 A+），支持按变更类型触发或批量触发，并记录回写前后快照。<strong>已交付</strong>，就是本页。
 *   <li><b>4.3.2.2 平台状态回流</b> —— 接收平台侧状态变化（审核通过 / 驳回 / 下架 / 抑制 …）。
 *       <strong>尚未开发</strong>；本页的在途任务由它在集成头交付后回报结果，在此之前
 *       显示「在途（等平台回报）」，<strong>不假装已回写</strong>。
 * </ul>
 *
 * <p>与 `/listings/publish`（4.3.1.2 批量发布）各挂一个工作区是同一手法：两者都是「把内容推给
 * 平台」，但它们是**两条独立通道** —— 各自的状态机、限流账与重试互不干涉，所以独立成页而不是
 * 做成列表的一个筛选维度。
 */
export default function ListingContentWritebackPage() {
  return (
    <div className="space-y-4">
      <ContentWritebackWorkspace />
    </div>
  );
}
