import { PurchaseAnalyticsWorkspace } from '@/components/purchase/purchase-analytics-workspace';

/**
 * 6.5.2.1 采购分析。
 *
 * <p>蓝图 06 卷：「采购侧报表：采购金额趋势、供应商集中度与贡献、采购价格波动、到货准时率、
 * 采购成本占销售额比、按类目/店铺维度的采购效率。输入：采购单与收货数据；时间范围与维度筛选。
 * 输出：报表图表 + 可导出明细；下钻到具体采购单。」
 *
 * <p>页面必须让三件事看得见：
 *
 * <ul>
 *   <li><b>口径写在报表块里</b>：集中度是 top-N 占比还是 HHI、准时率的分母是「承诺到货行」
 *       还是「收货行」、价格波动是环比还是同比 —— 后端给的原文**原样显示**，读的人不用猜。</li>
 *   <li><b>跨币种不合并</b>：金额按币种分列，**不给**把多个币种加起来的一个总数 ——
 *       那个总数是按 1:1 折算出来的假数。</li>
 *   <li><b>算不出来不是 0</b>：没有汇率快照、缺销售分母（13 利润与经营分析未交付）、
 *       上期没数据，一律显示「无数据」+ 原因。</li>
 * </ul>
 *
 * <p>前端路由是 {@code /purchase/analytics}（蓝图功能树里 6.5.2.1 的 L3 锚点），
 * 后端命名空间是 {@code /trade/purchase-analytics} —— 与 6.2.1.3 前端 /purchase/demand/plans ↔
 * 后端 /trade/purchase-plans、6.3.2.2 前端 /purchase/arrivals ↔ 后端 /trade/purchase-arrivals
 * 同一纪律。
 */
export default function PurchaseAnalyticsPage() {
  return <PurchaseAnalyticsWorkspace />;
}
