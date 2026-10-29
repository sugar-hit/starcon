import { AdAttributionWorkspace } from '@/components/ads/ad-attributions';

/**
 * 14.3.2.1 归因分析。
 *
 * <p>蓝图：「广告归因：把广告花费归因到 SKU/订单（支持平台归因窗口口径），计算广告后净利、
 * TACOS、增量效果估计。输入：平台广告归因数据；订单数据；利润数据；归因规则配置（末次点击/平台归因窗口）。
 * 输出：归因明细 + 广告后利润报表。关系：上游 = 14.3.1.3、13.4.1.2；下游 = 13.4 利润、5 定价。
 * <strong>归因规则必须固定并公示</strong>，规则变了历史报表不可比——这是广告与财务对账最容易吵架的点。
 * L3 锚点是 14.3.2「归因分析」。
 *
 * <p>🔴 页面顶部那段话不是提示语，是这一页的设计前提：
 *
 * <ol>
 *   <li><strong>归因规则必须固定并公示</strong>。规则是租户级配置，
 *       <strong>一个租户同一时刻只有一套生效口径</strong>；改规则 = <strong>版本 +1</strong>，
 *       而已算出的归因结果<strong>不重算、不改写</strong> —— 每条结果自带规则快照，
 *       所以历史报表仍然能被「当时那套口径」解释。服务端把规则原文随响应返回，页面原样展示。</li>
 *   <li><strong>与 14.3.1.3 的数字不是同一个数</strong>。14.3.1.3 答「这个商品上分摊了多少广告费」，
 *       本页答「这些订单是广告带来的吗」。两页的「广告后净利」不相等是<strong>正常的</strong>。</li>
 *   <li><strong>归不上的钱看得见</strong>。归不到 SKU、窗口内没有合格点击、平台没标记归因点击 ——
 *       这些金额在汇总里单列，<strong>不摊给任何 SKU，也不摊成 0</strong>。</li>
 *   <li><strong>不可比 ≠ 0</strong>。比率列的 <code>null</code> 渲染成「不可比」，
 *       <code>0</code> 才是「可比但真的是零」。</li>
 *   <li><strong>增量效果是推导值</strong>，不是平台给的事实 ⇒ 页面标「估算」，
 *       并把口径与边界（<strong>不是因果增量</strong>）一起显示。</li>
 * </ol>
 *
 * <p>路由用 <code>/ads/ad-attributions</code>（不是 <code>/ads/attributions</code>）——
 * 那个路径已经被 14.5.2.1「带货归因」占用；两个模块都叫「归因」但口径完全不同，
 * 共用一个路径会让运营把两者的数字对到一起（那必然对不上）。
 */
export default function AdAttributionsPage() {
  return <AdAttributionWorkspace />;
}
