import { AdCampaignReportWorkspace } from '@/components/ads/ad-campaign-reports';

/**
 * 14.3.1.1 活动报表。
 *
 * <p>蓝图：「活动维度效果报表：花费、曝光、点击、CTR、CPC、订单、转化率、销售额、ACOS/ROAS/TACOS，
 * 按日/周/月与多维对比，支持导出。输入：时间范围；维度；广告数据。
 * 输出：报表表 + 趋势图 + 口径说明。关系：上游 = 14.1.1.1；下游 = 14.3.2.1。
 * <strong>ACOS/ROAS/TACOS 三个口径要在表头写明定义</strong>，否则跨部门沟通会各说各话。
 * L3 锚点是 14.3.1「效果分析」。
 *
 * <p>🔴 页面顶部那段话不是提示语，是这一页的设计前提：
 *
 * <ol>
 *   <li><strong>口径写在表头</strong>。每一列的表头都带公式（<code>ACOS = Σ花费 / Σ广告销售额</code>），
 *       口径由服务端随报表一起返回 —— 前端不自己写第二份，浏览器里也不自己推。</li>
 *   <li><strong>TACOS 的「没有」看得见</strong>。总销售额口径（13.4 利润模块）未接入 ⇒
 *       TACOS 没有分母 ⇒ 表头写「未接入（原因）」，<strong>不给数</strong>。
 *       没有分母却给一个 0、或拿广告销售额顶替总销售额，比不给更糟。</li>
 *   <li><strong>不可比 ≠ 0</strong>。分母为 0 的比率渲染成「不可比」，
 *       0 是「可比但真的是零」。</li>
 *   <li><strong>不同币种不合并</strong>。服务端按币种拆开汇总，界面也按币种分块 ——
 *       前端一相加就造出一个服务端从没算过的假总数。</li>
 * </ol>
 *
 * <p>与同卷的 14.1.1.1「广告活动列表」是一对：那边管「有哪些活动、现在什么状态」，
 * 这一页管「这些活动效果如何」。两页的事实表是同一张（<code>ad_campaign_daily_metric</code>），
 * 所以两页的口径天然一致。
 */
export default function AdCampaignReportsPage() {
  return <AdCampaignReportWorkspace />;
}
