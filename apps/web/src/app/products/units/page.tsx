import { UnitGroupWorkspace } from '@/components/catalog/unit-group-workspace';

/**
 * 3.3.3.1 计量单位。路由是 `/products/units`（蓝图指定）。
 *
 * <p>与 `/products/sku/units`（3.1.2.4 单位与换算）**并存而不合并**：
 *
 * <ul>
 *   <li>这里是**字典视角**——单位字典、单位组、组内换算关系与换算试算，
 *       回答「千克和克几比一」这个问题本身，与哪个 SKU 无关；
 *   <li>那边是 **SKU 视角**——「这个 SKU 的箱与件几比一」，服务 06 采购 / 07 库存 / 08 拣货
 *       的单据生成与换算冻结快照。
 * </ul>
 *
 * <p>两者共用同一份单位字典，但字典行的写入所有权只有 3.1.2.4 一处；
 * 本页面的字典区块复用它的端点，而不是新开一个平行入口。
 */
export default function UnitsPage() {
  return <UnitGroupWorkspace />;
}