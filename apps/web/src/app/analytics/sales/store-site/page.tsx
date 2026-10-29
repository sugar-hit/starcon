import { AnalyticsStoreSiteWorkspace } from '@/components/analytics/store-site-workspace';

/**
 * 13.2.2.1 店铺与站点（卷 13「利润与经营分析」L3 13.2 销量分析 → 13.2.2 维度拆解）。
 *
 * <p>蓝图原文：「按店铺、站点、平台维度拆解销量与销售额，含店铺增长贡献、新店/老店区分、渠道占比。」
 * 输入「维度选择；时间范围。」输出「分组报表 + 占比与贡献度。」
 * 关系：上游 = 13.2.1.1；下游 = 13.1.1.2。
 *
 * <p><p>🔴 页面顶部那段话不是提示语，是这一页的设计前提：
 *
 * <ol>
 *   <li><strong>增长贡献度比绝对值有用</strong> —— 它告诉老板"哪个店/哪个品带来了增量"。
 *       所以这一页把<strong>增量归属</strong>放在和绝对值同等的位置，并且把
 *       「占比看当前窗口、贡献度看增量」这两个不同的口径分开说。</li>
 *   <li><strong>三个维度是同一棵树的三层</strong>（平台 → 站点 → 店铺）——
 *       换维度不是换一批数据，而是换同一批数据的切法。</li>
 *   <li><strong>「算不出来」与「等于 0」分开说</strong>。基期缺失、基期为 0、事实不存在，
 *       一律显示为「不可比 / 没有数据」，<strong>不用 0 冒充</strong> ——
 *       假 0 会被读成「这个店没卖出去」。</li>
 *   <li><strong>多币种绝不相加</strong>。成交金额按币种分别列出；只有件数与单数（与货币无关）
 *       给一个跨币种合计，它的币种显示为「与币种无关」。</li>
 *   <li><strong>站点维度有归属边界</strong>。订单不带站点，店铺启用多个站点时它的销量
 *       <strong>归不了任何站点</strong> —— 这一部分单列成「无法唯一归属」，
 *       <strong>不让用户以为「这个站点就是没卖出去」</strong>。</li>
 *   <li><strong>数据更新时间常驻</strong>。卷 13 卷首加粗句「数据延迟要显示」。</li>
 * </ol>
 */
export default function AnalyticsSalesStoreSitePage() {
  return <AnalyticsStoreSiteWorkspace />;
}
