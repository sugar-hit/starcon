import { WaybillListingWorkspace } from '@/components/logistics/waybill-listing-workspace';

/**
 * 10.2.1.1 运单列表。
 *
 * <p>蓝图：「运单主列表：运单号、订单/包裹、承运商渠道、发货仓、状态（待取号/已取号/
 * 已揽收/运输中/已签收/异常/已取消）、下单时间、重量、运费。」输出「列表 + 批量操作
 * （申请运单、取消、打印）」。
 *
 * <p>这是一个真实工作区而不是占位页：多维筛选、承运商侧状态的分色与免责说明、
 * 运费登记口径（未登记 ≠ 0）、三个真作用的批量动作（申请运单 / 取消 / 打印）
 * 全部落到 PostgreSQL 16，加载态 / 空态 / 错误态三态齐全。
 *
 * <p>路由取 {@code /logistics/shipments}，正是 10.2.1.2 页面注释里为本模块预留的
 * 路径；与 {@code /logistics/waybills}（「去申请运单号」）分得开。
 *
 * <p>数据来源如实标注：清单只读 10.2.1.2 的
 * {@code logistics_waybill_request}（**同库只读**，运单号的唯一写入所有者永远是它）；
 * 运费是本模块的登记事实（10.1.2.1 运费计算器未交付，系统算不出来）；
 * 打印只发已登记的面单凭据，**不渲染 ZPL**（8.3.4.2 未交付）；
 * 承运商侧的已揽收/在途/已签收/异常<b>没有数据源</b>（10.3 未交付），
 * 页面上禁用而不是画成 0 或留空。
 */
export default function LogisticsShipmentsPage() {
  return <WaybillListingWorkspace />;
}
