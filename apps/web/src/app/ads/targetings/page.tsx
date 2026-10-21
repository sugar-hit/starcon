import { AdTargetingsWorkspace } from '@/components/ads/ad-targetings';

/**
 * 14.1.3.2 投放定位。
 *
 * <p>蓝图：「管理投放定位（地域、人群、设备、时段、匹配类型），支持按定位维度看效果，
 * 指导出价调整。输入：定位设置；定位维度效果数据。输出：定位效果对比表 + 调整建议。
 * 关系：上游 = 14.1.2.1；下游 = 14.2.1.2 分时策略。
 * <strong>定位效果差异大时应收窄投放，无效定位的花费是最容易省下来的钱。</strong>
 *
 * <p>🔴 页面顶部那段话不是提示语，是这一页的设计前提：
 *
 * <ol>
 *   <li><strong>平台原文 ≠ 归一定位</strong>。上游 V281 的 {@code targetingSettings} 是
 *       <strong>平台原文串</strong>（不拆解、不归一），它的注释写明「解读归 14.1.3.2」
 *       ⇒ 本模块负责把原文<strong>拆解 + 归一</strong>成定位维度（地域 / 人群 / 设备 /
 *       时段 / 匹配类型），并<strong>保留原文</strong>以便回溯。归一不出来的键落
 *       {@code UNMAPPED}（显式合法值，不是兜底），不会被丢弃。</li>
 *   <li><strong>「不可比」≠ 0</strong>。销售额为 0 时 ACOS 是 {@code null}（算不出来），
 *       页面显示「不可比」—— 渲染成 {@code 0.00%} 就是在说「这个定位的 ACOS 是 0」。</li>
 *   <li><strong>跨币种不相加</strong>。对比表按币种分行，汇总的金额按币种分列；
 *       只有计数（与币种无关）才相加。</li>
 *   <li><strong>建议必须有依据</strong>。每条建议都带依据数值与口径；样本不足的建议
 *       标成「别照做」—— 没有依据的「建议」就是硬编码文案。</li>
 *   <li><strong>「本地已收窄」≠「平台侧已收窄」</strong>。平台回写队列未交付 ⇒ 本地写
 *       只落本地并标「待回写平台」，界面显式标出。</li>
 * </ol>
 *
 * <p>与 14.1.2.1 广告组管理是一对：那边管广告组自己的设置与投放商品，本页管
 * <strong>投给了谁</strong>（定位维度）、<strong>效果如何</strong>（对比表）与
 * <strong>该怎么调</strong>（建议 + 收窄）。
 */
export default function AdTargetingsPage() {
  return <AdTargetingsWorkspace />;
}
