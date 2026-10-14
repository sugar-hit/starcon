import { VariantCombinationWorkspace } from '@/components/catalog/variant-combination-workspace';

/**
 * 3.1.3.2 组合唯一性校验。路由挂在 3.1.2 SKU 入口的 /products/sku 之下，
 * 与规格轴定义（/products/sku/spec-axis）、编码与条码、单位与换算同级。
 *
 * <p>与规格轴定义分开成两个入口：规格轴管的是**结构**（有哪些轴、每个轴有哪些取值），
 * 本模块管的是**组合**（一组轴值是否重复、能不能生成草稿）。
 * 混在一个页面里会把「改一个取值」和「生成一批 SKU」变成同一个动作。
 */
export default function VariantCombinationPage() {
  return <VariantCombinationWorkspace />;
}
