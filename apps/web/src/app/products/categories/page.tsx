'use client';

import { CategoryTreeWorkspace } from '@/components/catalog/category-tree-workspace';

/**
 * 分类中心：3.3.1.1 分类树管理。
 *
 * <p>路由挂在商品中心根下（/products/categories），与 /products/attributes、
 * /products/sku/spec-axis 同级思路：三者都是**租户级的字典/结构配置**，被所有商品共用，
 * 而不是某个商品的详情页。后端对应 /catalog/categories。
 *
 * <p>本模块只做 3.3.1.1 分类树本身。3.3.1.2 类目属性要求挂在 /products/attributes
 * （那是 3.2.1.1 的属性中心，按属性组织），本页面不重复做它——两个入口做同一件事，
 * 用户反而分不清该去哪配。
 */
export default function CategoriesPage() {
  return <CategoryTreeWorkspace />;
}
