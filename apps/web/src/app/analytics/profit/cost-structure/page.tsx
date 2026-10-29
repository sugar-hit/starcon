import { AnalyticsCostStructureWorkspace } from '@/components/analytics/cost-structure-workspace';

/**
 * 13.4.2.1 成本结构（卷 13「利润与经营分析」L3 13.4.2 成本拆解下的第一格）。
 *
 * <p>蓝图原文：「分析成本构成占比：采购/头程/平台费/物流/广告/退货/其他，
 * 按 SKU、店铺、类目、时间维度拆解，看成本结构变化趋势。」
 * 输入「成本分摊结果；历史基线。」输出「占比结构图 + 趋势 + 异常提示。」
 * 关系：上游 = 12.4.2；下游 = 13.4.2.2。
 *
 * <p>🔴 页面顶部那段话不是提示语，是这一页的设计前提：
 *
 * <ol>
 *   <li><strong>「算不出来」与「占比是 0」分开说</strong>。分母缺失、没有事实、历史期数不足时，
 *       占比是**不可比**，**不是 0%** ——「占比 0.00%」正是蓝图加粗句要消灭的现象。</li>
 *   <li><strong>分母口径写在页面上</strong>。「占总成本」与「占收入」是两个口径，
 *       混着读会得出完全不同的结论。</li>
 *   <li><strong>基线来自真实历史</strong>。前 N 期同口径占比是**真从历史投影数据算出来的**，
 *       不是写死的基线常量；阈值也是可配置的（固定百分点 / 基线标准差的若干倍）。</li>
 *   <li><strong>多币种绝不相加</strong>。每一行带自己的币种。</li>
 *   <li><strong>数据更新时间常驻</strong>。卷 13 卷首加粗句「数据延迟要显示」。</li>
 * </ol>
 *
 * <p>与同卷的 13.4.2.2「费用差异」是一对：那边管**预计与实际差多少、往哪个方向偏**，
 * 这一页管**成本构成长什么样、哪一类在突然变重**。
 */
export default function AnalyticsProfitCostStructurePage() {
  return <AnalyticsCostStructureWorkspace />;
}
