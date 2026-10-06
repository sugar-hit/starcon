import { UnitConversionWorkspace } from '@/components/catalog/unit-conversion-workspace';

/**
 * 3.1.2.4 单位与换算。路由挂在 3.1.2.1 列表页的 /products/sku 之下，
 * 与列表（/products/sku）、详情（/products/sku/[id]）、编码与条码（/products/sku/code）
 * 共用同一个 SKU 入口。
 */
export default function UnitConversionPage() {
  return <UnitConversionWorkspace />;
}
