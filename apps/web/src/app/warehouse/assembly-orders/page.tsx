import { AssemblyOrderWorkspace } from '@/components/warehouse/assembly-order-workspace';

/**
 * 8.4.1.1 组装单（实物套装组装）。
 *
 * <p>蓝图 08 卷：「按 BOM 领用组件、消耗组件库存、产生成品 SKU 库存，同一事务完成。」
 * 输入「成品 SKU 与 BOM 版本；组件领用量；组装数量」，
 * 输出「组装单 + 组件出库分录 + 成品入库分录」。
 *
 * <p>页面必须让三条硬要求看得见：
 *
 * <ul>
 *   <li><b>虚拟套装走不到这里</b>：虚拟套装不持有库存，出库时由 09 订单直接展开组件扣减。
 *       所以本页只接受真实 catalog SKU，这条边界从后端 capabilities 读出来显示。</li>
 *   <li><b>应领量来自已发布的 BOM 版本</b>（catalog 3.5.2 规则 R7），建单时冻结成快照；
 *       之后 BOM 改版不影响已建的单。</li>
 *   <li><b>组件出库与成品入库是同一个本地事务</b>：所以「应领 / 实发」和「组装数量 / 成品入库量」
 *       两组数必须严格相等，页面把它们并排显示而不是藏起来。</li>
 * </ul>
 */
export default function AssemblyOrdersPage() {
  return <AssemblyOrderWorkspace />;
}
