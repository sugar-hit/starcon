import { AnalyticsTargetAttainmentWorkspace } from '@/components/analytics/target-attainment-workspace';

/**
 * 13.1.2.2 达成追踪（卷 13「利润与经营分析」L3 13.1.2 目标管理下的第二格）。
 *
 * <p>蓝图原文：「追踪目标达成：当前值、达成率、预测达成（按当前速度推算期末值）、未达标项高亮、
 * 人员/产品维度排名。」
 * 输入「13.1.2.1 目标；实际值投影；时间进度。」
 * 输出「达成率表 + 预测 + 预警。」
 * 关系：上游 = 13.1.2.1；下游 = 17 绩效核算、1 工作台预警。
 *
 * <p>🔴 页面顶部那段话不是提示语，是这一页的设计前提：
 *
 * <ol>
 *   <li><strong>预测达成值比当前值更有管理价值</strong>—— 月初就知道会不会达标，才有时间干预。
 *       所以「按当前速度推算的期末值」必须和当前值并排出现，并写明推算口径。</li>
 *   <li><strong>「算不出来」≠「等于 0」</strong>：拿不到实际值时显示「不可得」+ 原因。
 *       <strong>0% 达成率是有业务含义的断言</strong>（确实一件都没做成），<strong>「不可得」不是</strong>
 *       —— 页面绝不用 0% 顶替。</li>
 *   <li><strong>「没达标」≠「还没法判定」</strong>：未达标高亮只认「差一点」与「明显跟不上」；
 *       「周期尚未开始」「不可比」「实际值不可得」单列，不混进落后名单。</li>
 *   <li><strong>前端不做业务算术</strong>：达成率 / 预测达成率 / 时间进度一律来自服务端
 *       （{@code BigDecimal} 定标），页面只做展示格式化。</li>
 * </ol>
 *
 * <p>与同卷的 13.1.2.1「目标设定」是一组：那一页定<strong>要达成什么</strong>，
 * 这一页盯<strong>达成了多少</strong>、<strong>按当前速度能不能达成</strong>。
 */
export default function AnalyticsDashboardAttainmentPage() {
  return <AnalyticsTargetAttainmentWorkspace />;
}
