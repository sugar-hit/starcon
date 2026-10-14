import { SpecAxisWorkspace } from '@/components/catalog/spec-axis-workspace';

/**
 * 3.1.3.1 规格轴定义。路由挂在 3.1.2 SKU 入口的 /products/sku 之下，
 * 与编码与条码（/products/sku/code）、单位与换算（/products/sku/units）同级。
 *
 * <p>规格轴是租户级字典，要被多个 SPU 复用，因此工作区以「先维护字典与值源，再绑定到 SPU」
 * 的顺序组织，而不是按单个 SPU 展开。
 */
export default function SpecAxisPage() {
  return <SpecAxisWorkspace />;
}
