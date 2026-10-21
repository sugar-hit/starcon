'use client';

import { ListingBulkCollectWorkspace } from '@/components/listing-bulk-collect/listing-bulk-collect-workspace';

/**
 * 批量/插件采集：L3「4.4.1 商品采集」下的 4.4.1.2。
 *
 * <p>这个 L3 下有三个模块：
 *
 * <ul>
 *   <li><b>4.4.1.1 链接采集</b> —— 粘贴一个外部商品链接，抓成**采集草稿**。页面在
 *       {@code /listings/collect}。</li>
 *   <li><b>4.4.1.2 批量/插件采集</b> —— 按分类页、关键词搜索结果批量采集，以及浏览器插件在
 *       用户浏览时抓取当前页商品。<strong>就是本页</strong>。</li>
 *   <li><b>4.4.1.3 采集箱</b> —— 采集结果的暂存区，勾选后批量认领为内部商品。
 *       <strong>尚未开发</strong>。</li>
 * </ul>
 *
 * <p>🔴 <b>本页只做「批量」这一层</b>：单条链接采集在 4.4.1.1 的页面，暂存分组与批量认领是
 * 4.4.1.3 的职责 —— 本页不碰它们，免得两个模块的形状互相污染。
 *
 * <p>🔴 <b>采集只产生草稿</b>：本页没有「发布」「上架」「建档」按钮，也没有价格与库存输入框。
 * 草稿要进正式商品库，必须走 4.4.1.3 采集箱 → 3.1.1.1 商品建档，并补全属性与合规信息。
 *
 * <p>🔴 <b>抓取有频率限制与去重</b>：超并发 / 发起太密 / 同一来源刚被抓过，服务端会**明确拒绝**
 * 并写明还要等多少秒；同一个商品不会被抓两遍（已存在的直接跳过，不再次请求来源站点）。
 */
export default function ListingBulkCollectPage() {
  return (
    <div className="space-y-4">
      <ListingBulkCollectWorkspace />
    </div>
  );
}
