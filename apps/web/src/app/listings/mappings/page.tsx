'use client';

import { ListingMappingWorkspace } from '@/components/listing-mapping/listing-mapping-workspace';

/**
 * 映射管理：L3「4.1.2 变体映射」的锚点路由 `/listings/mappings`。
 *
 * <p>这个 L3 下有三个模块：
 *
 * <ul>
 *   <li><b>4.1.2.1 映射管理</b> —— 建立并维护「平台变体 → 内部 SKU/套装」的映射，
 *       支持一对一、一对多（套装）、多对一，以及按 SKU 编码 / 条码 / 标题相似度的批量候选
 *       （标题相似度恒需人工确认）。<strong>已交付</strong>，就是本页。</li>
 *   <li><b>4.1.2.2 映射版本</b> —— 版本列表与「按版本回溯受影响订单」。<strong>尚未开发</strong>。
 *       本页已把生效时间点与变更原因逐版写出，但「受影响订单范围」需要 09 订单的真实来源，
 *       页面上的能力声明里显式标了 available=false。</li>
 *   <li><b>4.1.2.3 套装组件映射</b> —— 组件 SKU 逐条明细与可售性校验。<strong>尚未开发</strong>。
 *       本页只把平台变体指向套装 SKU 并记组件数量。</li>
 * </ul>
 *
 * <p>路由挂在 `/listings/mappings` 而不是 `/listings` 下再开一个 tab：映射是**逐变体**成立的
 * （一个 listing 有多个变体，映射挂在变体上），与「Listing 列表」这种逐商品的视图不是同一层。
 * 与 `/listings/templates`、`/listings/category-mappings` 各挂一个工作区是同一手法。
 */
export default function ListingMappingsPage() {
  return (
    <div className="space-y-4">
      <ListingMappingWorkspace />
    </div>
  );
}
