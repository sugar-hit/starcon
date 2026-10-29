import { AnalyticsLogisticsAnalysisWorkspace } from '@/components/analytics/logistics-analysis-workspace';

/**
 * 13.5.3.1 物流分析（卷 13「利润与经营分析」L3 13.5.3 物流分析下的第一格）。
 *
 * <p>蓝图原文：「物流分析：单均运费、头程分摊后成本、渠道对比、时效达成率、
 * 各国成本对比、异常件占比。」输入「物流数据（10）；成本数据。」
 * 输出「物流报表 + 渠道对比。」
 *
 * <p>🔴 页面顶部那段话不是提示语，是这一页的设计前提：
 *
 * <ol>
 *   <li><strong>「算不出来」与「数值是 0」分开说</strong>。没有订单就没有单均运费、
 *       没有运单就没有异常件占比、缺项的分量是「—」，<strong>不是 0</strong>。</li>
 *   <li><strong>单均运费的分母是订单数</strong>。上游那个「每票运费」的分母是**票**，
 *       一单多票时两者不同 —— 页面上<strong>并排显示</strong>，不混为一谈。</li>
 *   <li><strong>头程分摊后成本读上游的分摊结果</strong>，本模块不重算一套分摊 ——
 *       两个分摊口径会让「物流成本占比」有两个答案。</li>
 *   <li><strong>多币种绝不相加</strong>。跨币种的合计不存在。</li>
 *   <li><strong>类目维度只覆盖头程分摊部分</strong>：尾程运费落在运单上，
 *       <strong>没有 SKU 维度</strong> ⇒ 那一段进不了类目。</li>
 *   <li><strong>数据更新时间常驻</strong>（卷 13 卷首加粗句「数据延迟要显示」）。</li>
 * </ol>
 *
 * <p>与同卷的 13.5.1.1「库存分析」、13.5.2.1「采购分析」是不同的视角：
 * 那两页管**货压在哪里**与**钱花在哪一家供应商身上**，
 * 这一页管**钱花在物流上多少、走得准不准、哪些类目必须单独定价**。
 */
export default function AnalyticsLogisticsAnalysisPage() {
  return <AnalyticsLogisticsAnalysisWorkspace />;
}
