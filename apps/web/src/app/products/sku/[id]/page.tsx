import { SkuDetailWorkspace } from '@/components/catalog/sku-detail-workspace';

/**
 * 3.1.2.2 SKU 详情。路由挂在 3.1.2.1 列表页的 /products/sku 之下，
 * 所以详情与列表共用同一个前端入口。
 */
export default async function SkuDetailPage({ params }: { params: Promise<{ id: string }> }) {
  const resolvedParams = await params;
  return <SkuDetailWorkspace skuId={resolvedParams.id} />;
}
