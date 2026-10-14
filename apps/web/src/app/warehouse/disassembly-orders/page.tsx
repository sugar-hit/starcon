import { DisassemblyOrderWorkspace } from '@/components/warehouse/disassembly-order-workspace';

/**
 * 8.4.2.1 拆解单（成品拆解回组件）。
 *
 * <p>蓝图 08 卷：「成品拆解回组件：消耗成品库存、产生组件库存（用于退换货拆解、样品回收）。」
 * 输入「成品 SKU 与数量；拆解去向（可用/待检/残次）」，输出「拆解单 + 反向库存分录」。
 *
 * <p>页面必须让四条硬要求看得见：
 *
 * <ul>
 *   <li><b>去向由接收方决定，不默认回可售</b>：建单时每一行都要显式选「可用 / 待检 / 残次」，
 *       下拉里没有「默认」这一项；执行后各去向的数量在列表页的<b>去向分布</b>里分开显示。</li>
 *   <li><b>应回量来自已发布的 BOM 版本 + 回收率</b>（catalog 3.5.2），建单时冻结成快照；
 *       之后 BOM 改版不影响已建的单。</li>
 *   <li><b>成品出库与组件入库是同一个本地事务</b>：所以「拆解数量 / 成品出库量」和
 *       「应回 / 实回」两组数必须严格相等，页面把它们并排显示而不是藏起来。</li>
 *   <li><b>执行是不可逆的</b>：成品已出库、组件已入库后单据是终态，
 *       不可改数、不可重复执行、也不可事后把去向改成可售。</li>
 * </ul>
 */
export default function DisassemblyOrdersPage() {
  return <DisassemblyOrderWorkspace />;
}
