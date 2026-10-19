'use client';

import { use } from 'react';
import { ListingDetailWorkspace } from '@/components/listing-detail/listing-detail-workspace';

/**
 * Listing 详情：4.1.1.2 的路由 `/listings/[id]`。
 *
 * <p>蓝图：「单个平台商品的全景：平台原始信息（ASIN/item_id、标题、状态、评分评论数）、内部映射、内容对照（平台当前内容 vs
 * 内部草稿内容）、价格库存差异、发布历史、平台操作记录。输入：`listing_id`；聚合平台快照与内部数据；用户权限。输出：左右对照视图 +
 * 编辑入口 + 差异高亮 + 回写按钮。」
 *
 * <p>🔴 数据来源全部是**本服务已交付的上游**：4.1.1.1 的 listing / 变体 / 状态事件（V171）、
 * 2.5.1.1 的平台写操作（V2）、2.5.2.1 的回写登记簿（V169），加上本模块自己的内容快照与发布历史（V175）。
 *
 * <p>🔴 这一层是纯展示壳：加载 / 失败 / 未找到三态与全部数据取数都在 {@link ListingDetailWorkspace} 里，
 * 本文件不引入任何 mock 数据。
 */
export default function ListingDetailPage({
  params,
}: {
  params: Promise<{ id: string }>;
}) {
  const { id } = use(params);
  return (
    <div className="space-y-4">
      <ListingDetailWorkspace listingId={id} />
    </div>
  );
}
