'use client';

import { ListingCollectBoxWorkspace } from '@/components/listing-collect-box/listing-collect-box-workspace';

/**
 * 采集箱：L3「4.4.1 商品采集」下的 4.4.1.3。
 *
 * <p>这个 L3 下有三个模块：
 *
 * <ul>
 *   <li><b>4.4.1.1 链接采集</b> —— 粘贴外部商品链接（1688 / 淘宝 / 供应商站 / 同行 listing），
 *       抓取标题、主图、规格、属性、描述，生成**采集草稿**。已交付，在 {@code /listings/collect}。
 *   <li><b>4.4.1.2 批量/插件采集</b> —— 按分类页、关键词搜索结果批量采集，以及浏览器插件在用户
 *       浏览时抓取当前页商品。已交付，在 {@code /listings/collect/bulk}。
 *   <li><b>4.4.1.3 采集箱</b> —— 采集结果的暂存区，按平台/来源/标签分组，勾选后批量认领为内部商品。
 *       <strong>就是本页</strong>。
 * </ul>
 *
 * <p>🔴 <b>认领时必须做品牌与分类映射</b>：认领规则里没有品牌映射就**建不出规则**，
 * 草稿的品牌属性映射不上就**认领不了** —— 否则采集来的商品归属混乱，后续无法按品牌做合规与绩效分析。
 *
 * <p>🔴 <b>认领只产出草稿</b>：本页不出现「已建档」「已入库商品」的字样。认领产出的是
 * 已映射、已命名、已预填内容的**商品草稿载荷**，落库归 3.1.1.1 商品建档。
 *
 * <p>🔴 <b>图片只透传地址</b>：不下载图片二进制、不写媒体表 —— 媒体库归 03 catalog，
 * 跨服务写表被 AGENTS.md 禁止。
 */
export default function ListingCollectBoxPage() {
  return (
    <div className="space-y-4">
      <ListingCollectBoxWorkspace />
    </div>
  );
}
