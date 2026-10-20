'use client';

import { ListingMigrationWorkspace } from '@/components/listing-migration/listing-migration-workspace';

/**
 * 批量搬迁：L3「4.4.2 跨店搬家」下的 4.4.2.2。
 *
 * <p>这个 L3 下有两个模块：
 *
 * <ul>
 *   <li><b>4.4.2.1 商品认领</b> —— 把已在 A 店铺的 listing 认领到 B 店铺，复用原商品内容与映射，
 *       生成 B 店铺的新 listing 草稿 + 待发布任务。在 <code>/listings/claim</code>。
 *   <li><b>4.4.2.2 批量搬迁</b> —— 按规则筛选（类目 / 销量区间 / 价格区间）把一批 listing 批量搬到
 *       多个店铺，<strong>搬迁时自动做标题差异化</strong>（否则平台可能判定重复铺货并抑制 listing），
 *       并给出可解释的重复铺货风险提示。<strong>已交付</strong>，就是本页。
 * </ul>
 *
 * <p>与 <code>/listings/claim</code> 并列而不是覆盖：认领是**一次**跨店搬家（单条 listing → 单个店铺），
 * 批量搬迁是**一批** listing → **多个**店铺，并且带筛选与重复度控制参数。两者产出的都是草稿 +
 * 待发布任务，派发仍然归 4.3.1.2 批量发布。
 */
export default function ListingMigrationsPage() {
  return (
    <div className="space-y-4">
      <ListingMigrationWorkspace />
    </div>
  );
}
