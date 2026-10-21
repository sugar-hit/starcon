'use client';

import { ListingCollectWorkspace } from '@/components/listing-collect/listing-collect-workspace';

/**
 * 链接采集：L3「4.4.1 商品采集」下的 4.4.1.1。
 *
 * <p>这个 L3 下有三个模块：
 *
 * <ul>
 *   <li><b>4.4.1.1 链接采集</b> —— 粘贴外部商品链接（1688 / 淘宝 / 供应商站 / 同行 listing），
 *       抓取标题、主图、规格、属性、描述，生成**采集草稿**。<strong>已交付</strong>，就是本页。
 *   <li><b>4.4.1.2 批量/插件采集</b> —— 按分类页、关键词搜索结果批量采集，以及浏览器插件在用户
 *       浏览时抓取当前页商品。<strong>尚未开发</strong>。
 *   <li><b>4.4.1.3 采集箱</b> —— 采集结果的暂存区，按平台/来源/标签分组，勾选后批量认领为内部商品。
 *       <strong>尚未开发</strong>。
 * </ul>
 *
 * <p>🔴 <b>本页只做「一个 URL 的一次采集」</b>：暂存分组与批量认领是 4.4.1.3 的职责，
 * 分类页与关键词批量是 4.4.1.2 的职责 —— 本页不碰它们，免得两个模块的形状互相污染。
 *
 * <p>🔴 <b>采集只产生草稿</b>：本页没有「发布」「上架」「建档」按钮，也没有价格与库存输入框。
 * 草稿要进正式商品库，必须走 4.4.1.3 采集箱 → 3.1.1.1 商品建档，并补全属性与合规信息。
 *
 * <p>🔴 <b>图片只记 URL</b>：采集不下载图片二进制、不写媒体表 —— 媒体库归 03 catalog，
 * 跨服务写表被 AGENTS.md 禁止。
 */
export default function ListingCollectPage() {
  return (
    <div className="space-y-4">
      <ListingCollectWorkspace />
    </div>
  );
}
