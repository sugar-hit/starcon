import { AdProductReportWorkspace } from '@/components/ads/ad-product-reports';

/**
 * 14.3.1.3 商品维度报表。
 *
 * <p>蓝图：「商品维度广告效果：SKU/SPU/Listing 维度的广告花费、广告销售额、总销售额、
 * ACOS、TACOS、广告占比利润。输入：商品维度；时间范围；利润数据（13）。
 * 输出：商品广告报表 + 广告销售占比 + 广告后净利。关系：上游 = 14.3.1.1、13.4.1.2；
 * 下游 = 13.4 利润。<strong>广告销售占比（广告单/总单）是判断广告是否带来增量还是抢自然单的关键</strong>，
 * 只看广告 ROAS 无法回答这个问题。L3 锚点是 14.3.1「效果分析」。
 *
 * <p>🔴 页面顶部那段话不是提示语，是这一页的设计前提：
 *
 * <ol>
 *   <li><strong>商品维度的数字是分摊出来的</strong>。广告事实只到<strong>活动</strong>级
 *       （<code>ad_campaign_daily_metric</code>），「投的是哪个商品」来自 14.1.1.2 的投放关系
 *       ⇒ 商品维度必须分摊。服务端把<strong>分摊规则原文</strong>随报表一起返回，
 *       页面原样展示 —— 前端不自己写第二份规则。规则变了历史报表就不可比。</li>
 *   <li><strong>直接归属与等分摊销分开标</strong>。每行带 <code>attributionMode</code>
 *       （DIRECT / ALLOCATED_EVENLY / MIXED）。把分摊值当平台原值看，会做出完全错误的投放决策。</li>
 *   <li><strong>归不到商品的花费看得见</strong>。汇总里单列，不摊给任何商品，也不摊成 0
 *       （摊成 0 就是说「这些广告没花钱」）。</li>
 *   <li><strong>不可比 ≠ 0</strong>。比率列的 <code>null</code> 渲染成「不可比」，
 *       <code>0</code> 才是「可比但真的是零」。</li>
 *   <li><strong>不同币种不合并</strong>。服务端按币种拆开汇总，界面也按币种分块 ——
 *       前端一相加就造出一个服务端从没算过的假总数。</li>
 * </ol>
 *
 * <p>与同卷的 14.3.1.1「活动报表」是一对：那边管「活动本身效果如何」，
 * 这一页管「这些效果落在哪些商品上」。两页的事实表是同一张
 * （<code>ad_campaign_daily_metric</code>），所以广告侧的口径天然一致；
 * 差别只在这一页多了一段<strong>利润侧</strong>（总销售额 / TACOS / 广告销售占比 /
 * 广告后净利 / 广告占比利润），它来自 13.4.1.2，跨服务只经授权只读 API。
 */
export default function AdProductReportsPage() {
  return <AdProductReportWorkspace />;
}
