import { AnalyticsAffiliateMarketingWorkspace } from '@/components/analytics/affiliate-marketing-workspace';

/**
 * 13.3.2.2 关联营销效果（卷 13「利润与经营分析」L3 13.3.2 流量与转化 → 13.3.2 下的第二格）。
 *
 * <p>蓝图原文：「分析站外/关联营销（关联视频、A+、Store 导流、社交媒体）的引流效果：
 * 曝光、点击、加购、成交归因。」输入「营销位配置与曝光点击数据；订单归因数据。」
 * 输出「营销位效果报表 + ROI。」关系：上游 = 4 刊登（营销模块）、14 广告；下游 = 13.3.1.1。
 *
 * <p><p>🔴 页面顶部那段话不是提示语，是这一页的设计前提：
 *
 * <ol>
 *   <li><strong>归因需要明确规则</strong>，否则各人看到的数不一样。所以这一页把
 *       <strong>(营销位, 归因规则)</strong> <strong>分列</strong>：每一行都贴着它用的那份
 *       <strong>规则快照</strong>（归因模型 / 分摊基准 / 归因窗口 / 退款口径 / 佣金口径），
 *       <strong>ROI 只在同一条规则内部相除</strong> —— 两条规则的钱绝不会被加到一起再除。</li>
 *   <li><strong>规则快照不是「当前规则」</strong>。用的是上游归因记录自带的快照，
 *       规则改了以后历史那一格仍然说得清它当时是怎么算出来的。</li>
 *   <li><strong>「算不出来」与「等于 0」分开说</strong>，「本轮不可用」也<strong>不等于 0</strong>。
 *       加购 / 关联视频 / Store 导流 / 达人站外曝光都显示「本轮不可用 + 归谁管 + 替代口径」，
 *       <strong>不显示 0</strong> —— 假 0 会被读成「这段时间没投放」。</li>
 *   <li><strong>两套口径分列</strong>：达人归因口径（成交归因 / 投入 / ROI）与落地商品口径
 *       （曝光 / 点击 / 点击率）是两个统计范围，<strong>每个值旁边都贴着口径标签</strong>。</li>
 * </ol>
 */
export default function AnalyticsProductsAffiliateMarketingPage() {
  return <AnalyticsAffiliateMarketingWorkspace />;
}
