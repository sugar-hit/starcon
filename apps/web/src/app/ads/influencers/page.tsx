import { InfluencerWorkspace } from '@/components/ads/influencers';

/**
 * 14.5.1.1 达人库。
 *
 * <p>蓝图：「管理达人/联盟资源：达人档案（类目、粉丝层级、带货 GMV、互动率、合作历史）、
 * 标签分组、评级与黑名单。输入：达人数据同步（平台接口或手动录入）；标签与评级。
 * 输出：达人库 + 筛选检索。关系：上游 = 平台数据；下游 = 14.5.1.2 建联、15 客户与客服。
 * <strong>达人效果数据（GMV/退款率）必须入库</strong>，否则无法筛选优质达人。」
 *
 * <p>🔴 页面顶部那段话不是提示语，是这一页的设计前提：
 *
 * <ol>
 *   <li><strong>「没录」必须看得见</strong>。GMV / 互动率 / 退款率没录显示「未录入」，
 *       <strong>不是 0.00</strong> —— 0 会被读成「这个达人一单没带过货」。</li>
 *   <li><strong>评级是算出来的</strong>。评分口径（权重 + 归一化上限）与档位都可配；
 *       必需的效果数据缺任何一项 ⇒ 该达人<strong>暂无评分</strong>（不是 0 分）。</li>
 *   <li><strong>「本地已录」不是「平台侧已录」</strong>。平台达人数据适配器未交付 ⇒
 *       同步入口明确返回「未接入」并<strong>不写任何数据</strong>。</li>
 *   <li><strong>黑名单有独立入口</strong>。拉黑后从正常检索里排除，解除留痕；
 *       黑名单页签让运营随时能看见「谁被拉黑了、为什么」。</li>
 *   <li><strong>两个 GMV 口径分开</strong>。档案 GMV（入库值）与合作历史合计（本地求和）
 *       分列两列；归因口径归 14.5.2.1，未交付。</li>
 * </ol>
 */
export default function InfluencerPage() {
  return <InfluencerWorkspace />;
}
