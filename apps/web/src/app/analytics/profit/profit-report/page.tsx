import { AnalyticsProfitReportWorkspace } from '@/components/analytics/profit-report-workspace';

/**
 * 13.4.1.1 多模式利润报表（卷 13「利润与经营分析」L3 13.4.1 利润报表下的第一格）。
 *
 * <p>蓝图原文：「按经营模式（自发货/全托管/半托管/平台仓）分别出利润报表，支持按天/月、
 * 按 MSKU/SKU/SPU/店铺/订单多维度核算。」输入「成本数据（12.4）、费用数据（12.3/12.4）、
 * 订单收入；模式标记（2.3.2.1）；汇率快照。」输出「利润报表（收入 - 各成本费用 = 毛利）
 * + 多维下钻 + 口径说明。」关系：上游 = 12 财务全部；下游 = 1 工作台、5 定价。
 *
 * <p>🔴 页面顶部那段话不是提示语，是这一页的设计前提：
 *
 * <ol>
 *   <li><strong>不同经营模式的利润含义完全不同</strong>（蓝图关系句加粗要求）。
 *       模式<strong>分行展示、绝不合并</strong>，也不提供「全部模式合计」——
 *       混在一张表里会得出错误结论。「未归属」也不是一种模式：
 *       挂不上店铺的钱单列，不塞进任何真实模式。</li>
 *   <li><strong>「算不出来」与「等于 0」分开说</strong>。某一类成本没有事实时它是
 *       <strong>—</strong>，毛利整格是<strong>—（不可比）</strong>，**不是 0.00** ——
 *       假 0 会被读成「完全没花钱」。</li>
 *   <li><strong>口径必须写在报表上</strong>（卷 13 强调）：收入怎么算、各项成本取自哪一路上游、
 *       汇率用哪个快照、哪些维度算不出来 —— 页面顶部与能力声明区都常驻显示。</li>
 *   <li><strong>多币种绝不相加</strong>。每一行带自己的币种；本位币合计只在显式指定且有
 *       上游汇率快照时给出，并把汇率与时点一起显示。</li>
 *   <li><strong>数据更新时间常驻</strong>。卷 13 卷首加粗句「数据延迟要显示」。</li>
 * </ol>
 *
 * <p>与同 L3 的兄弟模块 13.4.1.2「SKU 利润」是一对：那边管 <strong>SKU 级</strong>利润的
 * 口径，这一页管 <strong>按经营模式</strong>的利润报表与多维核算。
 */
export default function AnalyticsProfitProfitReportPage() {
  return <AnalyticsProfitReportWorkspace />;
}
