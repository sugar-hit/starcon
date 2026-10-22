import { AdPromotionWorkspace } from '@/components/ads/ad-promotions';

/**
 * 14.4.1.1 促销列表。
 *
 * <p>蓝图：「管理平台促销活动：促销类型、适用商品、折扣幅度、起止时间、预算上限、参与店铺，
 * 同步状态与效果。输入：促销定义；平台促销接口；适用商品选择。输出：促销记录 + 平台同步状态。
 * 关系：上游 = 5 定价（促销价计算）；下游 = 平台促销 API、12 财务（费用）。
 * <strong>促销的毛利必须实时测算</strong>——折扣叠加后亏损是常态，需在下单前拦截或预警。」
 *
 * <p>🔴 页面顶部那段话不是提示语，是这一页的设计前提：
 *
 * <ol>
 *   <li><strong>毛利当前不可用，必须常驻说明</strong>。上游 5 定价在 catalog（跨服务不可读）
 *       且没有促销价测算端点 ⇒ 拿不到成本，<strong>毛利未测算</strong>。
 *       页面<strong>绝不能</strong>显示一个假的 0 毛利 —— 那个数字会被当成「这单不赚钱」，
 *       而真相是「拿不到成本」。</li>
 *   <li><strong>「本地已建」不是「平台侧已建」</strong>。平台促销 API 未交付 ⇒ 平台同步状态
 *       常驻「本地已建，平台侧还没有」。</li>
 *   <li><strong>折后价可算、不可比要写清楚</strong>。折后价 = 基准价 × (1 − 折扣率)，
 *       基准价是促销定义里登记的输入（listing 没有价格列 —— 售价归 05 定价）；
 *       没登记基准价时显示「不可比」，<strong>不是 0.00</strong>。</li>
 *   <li><strong>拦截是真拦</strong>。折扣幅度超阈值、折后价低于下限、促销叠加三条规则会
 *       <strong>拦住排期与上线</strong>，改完必须重跑检查才会放行。</li>
 * </ol>
 */
export default function AdPromotionPage() {
  return <AdPromotionWorkspace />;
}
