import { AnalyticsStoreComparisonWorkspace } from '@/components/analytics/store-comparison-workspace';

/**
 * 13.1.1.2 多店对比（卷 13「利润与经营分析」L3 13.1.1 经营看板下的第二格）。
 *
 * <p>蓝图原文：「多店铺横向对比：规模、增长、利润率、广告效率、退货率、库存周转，
 * 找出掉队店铺与增长店铺，支持按平台分组。」
 * 输入「店铺维度聚合；对比指标选择。」
 * 输出「对比矩阵 + 排名 + 差异归因提示。」
 * 关系：上游 = 13.1.1.1；下游 = 各店铺的专题分析。
 *
 * <p>🔴 页面顶部那段话不是提示语，是这一页的设计前提：
 *
 * <ol>
 *   <li><strong>跨平台口径差异必须标注</strong>（蓝图关系句加粗要求：「亚马逊的 GMV 与 TikTok 的
 *       口径不同，对比时标注口径说明」）。矩阵里同时给「平台侧成交金额」与「还原口径成交金额」，
 *       两者之差是「口径差异额」；<strong>不同平台的数字不能直接排名</strong>，
 *       所以每个平台分组都挂了自己的口径说明。</li>
 *   <li><strong>「算不出来」与「等于 0」分开说</strong>。上一等长窗口没有事实、或分母为 0 时，
 *       增长率是<strong>不可比</strong>，<strong>不是 0%</strong> —— 假 0 会被读成「这个店铺销售为零」。</li>
 *   <li><strong>多币种绝不相加</strong>。每一行带自己的币种，排名也在币种内做 ——
 *       一个 USD 店铺和一个 EUR 店铺的大小本来就不可比。</li>
 *   <li><strong>掉队不等于错误</strong>。掉队店铺是经营状态（要去救），
 *       不是异常 —— 所以它是 warning 而不是 danger。</li>
 *   <li><strong>数据更新时间常驻</strong>。卷 13 卷首加粗句「数据延迟要显示」。</li>
 * </ol>
 *
 * <p>与同卷的 13.1.1.1「经营总览」是一对：那边看**公司维度**的整体经营，
 * 这一页看**店铺维度**的横向对比 —— 找出掉队店铺与增长店铺。
 */
export default function AnalyticsDashboardStoreComparisonPage() {
  return <AnalyticsStoreComparisonWorkspace />;
}
