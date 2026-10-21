import { AdCouponWorkspace } from '@/components/ads/ad-coupons';

/**
 * 14.4.1.2 优惠券与秒杀 —— 优惠券。
 *
 * <p>蓝图：「管理优惠券（折扣码、满减、买赠）与秒杀活动：生成码、设置库存与限领、
 * 投放渠道（站内/站外/社媒），跟踪领取与核销。输入：券规则；投放计划；核销数据同步。
 * 输出：券记录 + 核销统计。关系：上游 = 5 定价；下游 = 平台营销接口、15 客户（定向发放）。
 * <strong>券的核销要与订单绑定</strong>，否则无法评估活动真实效果与补贴成本。」
 *
 * <p>🔴 页面顶部那段话不是提示语，是这一页的设计前提：
 *
 * <ol>
 *   <li><strong>门槛与面额是快照</strong>。上游 5 定价在 catalog（跨服务不可读）⇒
 *       券的门槛 / 面额 / 折扣率是<strong>建券时登记的定价口径</strong>，
 *       页面要常驻说明，不许让人以为是实时联动价。</li>
 *   <li><strong>「本地已建」不是「平台侧已建」</strong>。平台营销接口未交付 ⇒ 页面常驻
 *       「平台侧还没有这张券」。</li>
 *   <li><strong>核销必须带订单标识</strong>。表单里订单标识是必填 —— 没有订单标识的核销
 *       <strong>写不进来</strong>（蓝图加粗句）。</li>
 *   <li><strong>不可测算不是 0</strong>。买赠券的补贴成本显示「不可测算」，
 *       <strong>不是 0.00</strong> —— 0 会被当成「补贴成本是 0」的假事实。</li>
 *   <li><strong>限领看的是累计</strong>。「每人限领」说的是「这个人在这张券上累计领了几张」，
 *       超出服务端直接拒绝。</li>
 * </ol>
 */
export default function AdCouponPage() {
  return <AdCouponWorkspace />;
}
