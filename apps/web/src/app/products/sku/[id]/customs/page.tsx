import { CustomsAttributeWorkspace } from '@/components/catalog/customs-attribute-workspace';

/**
 * 3.7.1.1 HS 编码与申报要素。
 *
 * <p>路由挂在 SKU 详情之下，与物理与安全属性（/products/sku/[id]/physical）同一思路：
 * 它是「针对某一个 SKU 的一个侧面」，而不是一个与 SKU 无关的合规列表页。
 *
 * <p>蓝图把这一格挂在 3.7 商品合规下面，但它问的是**某一个 SKU 申报什么**，
 * 采集时点也应当尽量前置到 SKU 建档 —— 所以按 SKU 寻址比按合规台账寻址更贴合使用场景。
 *
 * <p>与 3.7.2.1 的差别在审核：这一页带审核状态机与审核动作，
 * 所以它必须是独立工作区（不能只在详情页里只读展示）。
 */
export default async function CustomsAttributePage({
  params,
}: {
  params: Promise<{ id: string }>;
}) {
  const resolvedParams = await params;
  return <CustomsAttributeWorkspace skuId={resolvedParams.id} />;
}
