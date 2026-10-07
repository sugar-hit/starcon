'use client';

import { BrandWorkspace } from '@/components/catalog/brand-workspace';

/**
 * 品牌管理：3.3.2.1。
 *
 * <p>路由是 `/products/brands`，与 `/products/categories`（3.3.1.1 分类树）并列：
 * 品牌与类目是两份平行的租户级字典，都被 SPU 引用，但维护节奏与责任人不同
 * （类目跟商品结构走，品牌跟授权合约走），放在同一个页面会让两边的编辑互相干扰。
 *
 * <p>「关联类目」是品牌指向类目的引用，所以详情页会链回分类中心；
 * 反向不成立——类目那边不知道有哪些品牌，那是本模块的列表筛选要回答的问题。
 */
export default function BrandsPage() {
  return <BrandWorkspace />;
}
