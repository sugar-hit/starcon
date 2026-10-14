import { PhysicalSafetyWorkspace } from '@/components/catalog/physical-safety-workspace';

/**
 * 3.7.2.1 物理与安全属性。
 *
 * <p>路由挂在 3.1.2.2 的 SKU 详情之下，与属性覆写（/products/sku/[id]/attributes）
 * 同一思路：它是「针对某一个 SKU 的一个侧面」，而不是商品档案本身的一部分。
 *
 * <p>蓝图的 L3 锚点路由是 `—`（3.7 挂在 /products/compliance 下面），但职责句
 * 明确写着「这些属性直接决定物流可用性，**采集时点应尽量前置到 SKU 建档**」——
 * 所以放在 SKU 详情下作为独立工作区，比放在一个跟 SKU 无关的合规列表页里
 * 更贴合「建档时就填」这个要求。
 */
export default async function PhysicalSafetyPage({ params }: { params: Promise<{ id: string }> }) {
  const resolvedParams = await params;
  return <PhysicalSafetyWorkspace skuId={resolvedParams.id} />;
}
