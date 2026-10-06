import { SkuCodeWorkspace } from '@/components/catalog/sku-code-workspace';

/**
 * 3.1.2.3 编码与条码。路由挂在 3.1.2.1 列表页的 /products/sku 之下，
 * 与列表（/products/sku）、详情（/products/sku/[id]）共用同一个 SKU 入口。
 */
export default function SkuCodePage() {
  return <SkuCodeWorkspace />;
}