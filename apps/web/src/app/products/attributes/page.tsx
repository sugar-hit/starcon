import { AttributeDictionaryWorkspace } from '@/components/catalog/attribute-dictionary-workspace';

/**
 * 3.2.1.1 属性定义列表。
 *
 * <p>属性字典是**租户级**的全局配置，被所有商品共用，所以路由挂在商品中心根下，
 * 而不是挂在某个商品的详情页里。与 3.1.3.1 的规格轴定义（/products/sku/spec-axis）同级思路：
 * 两者都是字典，只是被绑定的对象不同。
 *
 * <p>工作区按「先把字典与取值准备好、再挂到类目上」的顺序组织，
 * 而不是按单个商品展开——这里没有任何一个商品是主角。
 */
export default function AttributesPage() {
  return <AttributeDictionaryWorkspace />;
}