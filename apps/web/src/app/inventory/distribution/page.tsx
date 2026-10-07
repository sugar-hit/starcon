import { InventoryDistribution } from '@/components/inventory/inventory-distribution';

/**
 * 7.1.3.1 多仓分布视图的独立路由。
 *
 * <p>这个路由是多仓矩阵的**契约地址**：矩阵行里的操作入口带 `?skuId=&spuId=` 定位参数，
 * 从别处跳进来时按契约把 SKU/SPU 接上，打开就能看到那个 SKU 的多仓分布。
 *
 * <p>台账里 7.1.3.1 行的「L3 锚点路由」是 `—`（L3 锚点行本身没写路由），所以这里
 * 不去改蓝图，只把页面地址记在该行证据列里。
 */
export default function InventoryDistributionPage({
  searchParams,
}: {
  searchParams: Record<string, string | string[] | undefined>;
}) {
  const first = (key: string) => {
    const v = searchParams?.[key];
    return Array.isArray(v) ? (v[0] ?? '') : (v ?? '');
  };
  return <InventoryDistribution initialSkuId={first('skuId') || null} />;
}
