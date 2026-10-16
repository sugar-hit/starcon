'use client';

import { PlatformMappingWorkspace } from '@/components/catalog/platform-mapping-workspace';

/**
 * 平台类目映射：L3「4.2.3 平台与站点适配」的锚点路由 `/listings/category-mappings`。
 *
 * <p>本 L3 下：
 *
 * <ul>
 *   <li><b>4.2.3.1 平台类目映射</b> —— 内部分类 ↔ 平台类目的多对多映射（带平台 + 站点两个维度），
 *       外加平台的某个属性对应内部哪个属性 ID 的映射规则。<strong>已交付</strong>，就是本页。
 *   <li><b>4.2.3.2 合规预检</b> —— 刊登前的平台合规检查。<strong>尚未开发</strong>。
 * </ul>
 *
 * <p>页面挂在这个路径而不是 `/products/categories` 之下：分类树（3.3.1.1）管的是
 * 「这个类目本身是什么」，本模块管的是「这个类目在某个平台某个站点对应哪个平台类目」——
 * 后者会随平台改版频繁变动，挂在同一个前缀下会让「改类目名」和「改平台映射」挤进一个页面。
 */
export default function ListingCategoryMappingsPage() {
  return (
    <div className="space-y-4">
      <PlatformMappingWorkspace />
    </div>
  );
}
