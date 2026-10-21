import { AdFlashSaleWorkspace } from '@/components/ads/ad-flash-sales';

/**
 * 14.4.1.2 优惠券与秒杀 —— 秒杀。
 *
 * <p>蓝图：「管理优惠券（折扣码、满减、买赠）与<strong>秒杀活动</strong>：生成码、
 * 设置库存与限领、投放渠道（站内/站外/社媒），跟踪领取与核销。……
 * <strong>券的核销要与订单绑定</strong>，否则无法评估活动真实效果与补贴成本。」
 *
 * <p>🔴 页面顶部那段话不是提示语，是这一页的设计前提：
 *
 * <ol>
 *   <li><strong>秒杀价与原价都是快照</strong>。上游 5 定价在 catalog（跨服务不可读）⇒
 *       这是<strong>建活动时登记的定价口径</strong>，页面要常驻说明。</li>
 *   <li><strong>「本地已建」不是「平台侧已建」</strong>。平台营销接口未交付 ⇒ 页面常驻
 *       「平台侧还没有这个秒杀活动」。</li>
 *   <li><strong>核销必须带订单标识</strong>，且<strong>不可比不是 0</strong>：原价没登记时
 *       让利额显示「不可比」，<strong>不是 0.00</strong>。</li>
 * </ol>
 */
export default function AdFlashSalePage() {
  return <AdFlashSaleWorkspace />;
}
