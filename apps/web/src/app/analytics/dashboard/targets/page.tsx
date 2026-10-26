import { AnalyticsTargetSettingWorkspace } from '@/components/analytics/target-setting-workspace';

/**
 * 13.1.2.1 目标设定（卷 13「利润与经营分析」L3 13.1.2 目标管理下的第一格）。
 *
 * <p>蓝图原文：「设定经营目标：按部门/人员/产品/店铺设定销售额、毛利额、毛利率、广告 ROI、
 * 库存周转等指标与周期，支持分解到月/周。」
 * 输入「目标定义（指标、目标值、周期、归属对象）；历史基线参考。」
 * 输出「目标记录 + 分解计划。」
 * 关系：上游 = 17 组织（部门人员）；下游 = 13.1.2.2 达成追踪。
 *
 * <p>🔴 页面顶部那段话不是提示语，是这一页的设计前提：
 *
 * <ol>
 *   <li><strong>目标必须挂在组织对象上</strong>（蓝图关系句加粗要求：「目标要挂在组织对象上
 *       （部门/人/店铺），否则无法与绩效核算对接（行业普遍需求）」）。所以归属对象是必选项，
 *       没有归属对象的目标<strong>不给出提交入口</strong>。</li>
 *   <li><strong>口径唯一：目标只引用，不重算</strong>。销售额在店铺维度是<strong>三个并列口径</strong>
 *       （平台侧 / 还原口径 / 口径差异额）—— 取其一改名叫「销售额」就是抹掉口径差异，
 *       所以三个都列出来，让用户看见差异再选。</li>
 *   <li><strong>没登记口径码的指标如实显示「未登记」</strong>（广告 ROI 的组织维度、库存周转），
 *       并给出归属模块 —— 页面<strong>不许拿别的指标冒充</strong>。</li>
 *   <li><strong>「算不出来」≠「等于 0」</strong>：历史基线取不到时显示「—」+ 原因。</li>
 *   <li><strong>分解项合计精确等于目标值</strong>，余数归属写在页面上（不静默差 0.01）。</li>
 * </ol>
 *
 * <p>与同卷的 13.1.1.1「经营总览」、13.1.1.2「多店对比」是一组：
 * 那两边看<strong>已经发生了什么</strong>，这一页定<strong>要达成什么</strong>；
 * 下游 13.1.2.2「达成追踪」接的就是这一页产出的「目标记录 + 分解计划」。
 */
export default function AnalyticsDashboardTargetsPage() {
  return <AnalyticsTargetSettingWorkspace />;
}
