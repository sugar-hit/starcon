import { InfluencerAttributionWorkspace } from '@/components/ads/influencer-attributions';

/**
 * 14.5.2.1 带货归因。
 *
 * <p>蓝图：「达人带货归因：达人带来的订单量、GMV、退款率、佣金成本、有效粉丝转化，
 * 对比达人投入产出，沉淀优质达人库。输入：达人带货数据同步；订单归因；佣金数据。
 * 输出：达人效果报表 + ROI + 评级更新。关系：上游 = 14.5.1.1、平台数据；
 * 下游 = 14.5.1.1 评级、13.4 利润（佣金成本）。
 * <strong>达人佣金是真实成本，必须进利润表</strong>，否则会高估达人带货的净收益。」
 *
 * <p>🔴 页面顶部那段话不是提示语，是这一页的设计前提：
 *
 * <ol>
 *   <li><strong>三个 GMV 口径分列三栏</strong>。档案 GMV（14.5.1.1 入库值）、
 *       合作历史合计（本地求和）、归因口径（本模块按规则分摊后的净额）
 *       <strong>谁也不覆盖谁</strong> —— 看板与跨部门沟通必须分别引用。</li>
 *   <li><strong>佣金成本还没进利润表</strong>。13.4 利润（reporting 服务）未交付 ⇒
 *       佣金只在本服务内留痕，页面<strong>不许</strong>写「已同步到利润分析」；
 *       「扣佣金后净额」<strong>只扣佣金</strong>（样品 / 履约 / 平台费不在里面），
 *       不是净收益。</li>
 *   <li><strong>「没录」必须看得见</strong>。GMV / 佣金 / ROI 没录显示「未录入」，
 *       <strong>不是 0.00</strong> —— 0 会被读成「这个达人不用付佣金、ROI 无穷大」。</li>
 *   <li><strong>归因规则固定并公示</strong>。一个租户只有一套生效口径；
 *       每条归因记录带自己的规则快照与基数快照，<strong>「归因 GMV = 基数 × 权重」</strong>
 *       永远可复核。</li>
 *   <li><strong>平台带货数据同步未接入</strong> ⇒ 同步入口明确返回「未接入」
 *       并<strong>不写任何数据</strong>。</li>
 * </ol>
 */
export default function InfluencerAttributionPage() {
  return <InfluencerAttributionWorkspace />;
}
