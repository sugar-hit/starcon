import { AnalyticsLifecycleSegmentationWorkspace } from '@/components/analytics/lifecycle-segmentation-workspace';

/**
 * 13.3.1.2 生命周期与分层（卷 13「利润与经营分析」L3 13.3.1 产品分析下的第二格）。
 *
 * <p>蓝图原文：「按生命周期与表现分层商品：新品、爆款、旺款、平款、滞销款、衰退款，
 * 按 ABC 分类（销量/金额），输出分层结果与建议动作。」输入「销量与毛利历史；分层规则配置。」
 * 输出「分层结果 + 各层建议（备货/清仓/下架/推广）。」
 * 关系：上游 = 13.3.1.1；下游 = 6.2.1.2 备货、5.4 调价、7.6.1 呆滞。
 *
 * <p><p>🔴 页面顶部那段话不是提示语，是这一页的设计前提：
 *
 * <ol>
 *   <li><strong>分层是资源分配的依据</strong>：爆款备货、滞销清仓，没有分层就只能平均用力。
 *       所以每一档都挂着一个**建议动作**（备货 / 清仓 / 下架 / 推广），并写清依据。</li>
 *   <li><strong>口径引用，不重复登记</strong>：销量 / 销售额的口径在 <strong>13.2.2.2</strong>，
 *       毛利额的口径在 <strong>13.3.1.1</strong>（派生：销售额 − 出库成本）——
 *       本页只<strong>引用</strong>，不另起一套口径表登记同一批指标。</li>
 *   <li><strong>「算不出来」与「等于 0」分开说</strong>。档位算不出来（历史不足 / 窗口内没有事实 /
 *       口径未投影）、毛利额算不出来（出库成本没投影），一律显示为「不可比 / 没有数据」+ 原因，
 *       <strong>不用 0 或某个默认档位冒充</strong> —— 假 0 会被读成「这个商品不赚钱」。</li>
 *   <li><strong>金额按币种分行</strong>，ABC 按金额<strong>在同一个币种内部</strong>排名 ——
 *       金额跨币种不可比，前端也绝不自己把两个币种的金额加起来。</li>
 *   <li><strong>数据更新时间常驻</strong>。卷 13 卷首加粗句「数据延迟要显示」。</li>
 * </ol>
 */
export default function AnalyticsProductsLifecyclePage() {
  return <AnalyticsLifecycleSegmentationWorkspace />;
}
