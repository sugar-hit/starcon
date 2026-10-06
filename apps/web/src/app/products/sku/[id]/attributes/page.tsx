import { SkuAttributeOverrideWorkspace } from '@/components/catalog/sku-attribute-override-workspace';

/**
 * 3.2.2.2 SKU 属性覆写。
 *
 * <p>路由挂在 3.1.2.2 的 SKU 详情之下，与编码（/products/sku/code）、单位
 * （/products/sku/units）这些 SKU 级工作区同级思路：它们都是"针对某一个 SKU 的一个侧面"，
 * 而不是商品档案本身的一部分。
 *
 * <p>与详情页分工：详情页的属性区是**只读**的有效值展示（回答"这个 SKU 现在是什么参数"），
 * 这里负责**改**它（回答"哪条值不对，要覆写、清空还是取消覆写"）。
 */
export default function SkuAttributeOverridePage({ params }: { params: { id: string } }) {
  return <SkuAttributeOverrideWorkspace skuId={params.id} />;
}
