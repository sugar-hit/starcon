'use client';

import { ListingWorkspace } from '@/components/listings/listing-workspace';

/**
 * Listing 列表：L3「4.1.1 Listing 管理」的锚点路由 `/listings`。
 *
 * <p>这个 L3 下有三个模块：
 *
 * <ul>
 *   <li><b>4.1.1.1 Listing 列表</b> —— 按店铺 / 站点 / 状态 / 负责人 / 类目 / 是否有映射 /
 *       是否缺货筛选平台商品，含批量分配负责人与批量下架。<strong>已交付</strong>，就是本页。
 *   <li><b>4.1.1.2 Listing 详情</b> —— 单个平台商品的全景与差异对照。<strong>尚未开发</strong>。
 *   <li><b>4.1.1.3 生命周期与下架</b> —— 七态状态机与平台回写。<strong>尚未开发</strong>。
 * </ul>
 *
 * <p>现在这一页先只挂列表：详情与生命周期都建立在本页产出的 listing 与变体之上，
 * 等 4.1.1.2 / 4.1.1.3 交付后再从列表行下钻 —— 与 `/listings/templates`、
 * `/listings/category-mappings` 各挂一个工作区是同一手法。
 */
export default function ListingsPage() {
  return (
    <div className="space-y-4">
      <ListingWorkspace />
    </div>
  );
}
