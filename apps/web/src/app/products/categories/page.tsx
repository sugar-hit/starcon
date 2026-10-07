'use client';

import { CategoryTreeWorkspace } from '@/components/catalog/category-tree-workspace';

/**
 * 分类中心：3.3.1.1 分类树管理。
 *
 * <p>路由挂在商品中心根下（/products/categories），与 /products/attributes、
 * /products/sku/spec-axis 同级思路：三者都是**租户级的字典/结构配置**，被所有商品共用，
 * 而不是某个商品的详情页。后端对应 /catalog/categories。
 *
 * <p>本模块只做 3.3.1.1 分类树本身（树结构、层级、防环、启用停用）。
 * 3.3.1.2 类目属性要求是同一个类目中心的另一个页面：/products/categories/requirements。
 * 之所以不塞进这个页面——「改类目名」和「改这个类目的必填项」是两件独立的事，
 * 挤在一屏里编辑时会互相覆盖对方正在改的内容；拆开后由本页链过去。
 */
export default function CategoriesPage() {
  return <CategoryTreeWorkspace />;
}
