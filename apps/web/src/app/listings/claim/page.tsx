'use client';

import { ListingClaimWorkspace } from '@/components/listing-claim/listing-claim-workspace';

/**
 * 商品认领：L3「4.4.2 跨店搬家」下的 4.4.2.1。
 *
 * <p>这个 L3 下有两个模块：
 *
 * <ul>
 *   <li><b>4.4.2.1 商品认领</b> —— 把已在 A 店铺的 listing 认领到 B 店铺，复用原商品内容与映射，
 *       生成 B 店铺的新 listing 草稿 + 待发布任务。<strong>已交付</strong>，就是本页。
 *   <li><b>4.4.2.2 批量搬迁</b> —— 批量把一批 listing 从一个店铺搬到多个店铺，并对重复铺货风险
 *       给出提示。<strong>尚未开发</strong>；本页的「标题逐字沿用」会提示重复铺货风险，
 *       但标题差异化本身是 4.4.2.2 的职责，这里只提示、不代改。
 * </ul>
 *
 * <p>与 `/listings/publish`（4.3.1.2 批量发布）各挂一个工作区是同一手法：认领产出的是
 * <strong>草稿</strong>，派发是另一件事 —— 认领只把草稿与待发布任务入队，真正发给平台的是
 * 4.3.1.1 一键刊登（尚未开发）与 4.3.1.2 批量发布。
 */
export default function ListingClaimPage() {
  return (
    <div className="space-y-4">
      <ListingClaimWorkspace />
    </div>
  );
}
