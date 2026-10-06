'use client';

import { CategoryRequirementsWorkspace } from '@/components/catalog/category-requirements-workspace';

/**
 * 类目属性要求：3.3.1.2。
 *
 * <p>路由是 `/products/categories/requirements`，挂在分类中心之下：
 * 属性要求是**挂在某个类目上**的配置，离开类目就无从谈起，
 * 放在 `/products/attributes`（按属性组织）会让「这个类目要求什么」变成要跨页拼出来的问题。
 *
 * <p>与 `/products/categories`（3.3.1.1 分类树）的分工：那边维护树的结构与节点，
 * 这边维护每个节点上的属性要求。一个页面做两件事会让「改类目名」和「改必填项」互相干扰，
 * 所以拆成两个页面，由分类树页面链过来。
 */
export default function CategoryRequirementsPage() {
  return <CategoryRequirementsWorkspace />;
}
