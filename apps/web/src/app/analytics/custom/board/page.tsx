import { BoardWorkspace } from '@/components/analytics/board-workspace';

/**
 * 13.6.1.2 看板搭建（卷 13「利润与经营分析」L3 13.6.1 下的第二格）。
 *
 * <p>蓝图原文：「搭建自定义经营看板：拖拽组件、选择指标与筛选、布局保存、按角色共享、
 * 设置默认值。」输入「组件配置；看板模板；可见范围设置。」输出「看板视图 + 共享配置。」
 * 关系：上游 = 13.6.1.1；下游 = 1 工作台（可置为个人看板）。
 * <strong>看板需权限过滤</strong>，不同角色看到的组件不同，不能一个看板通吃。
 *
 * <p><b>🔴 页面顶部那段话不是提示语，是这一页的设计前提</b>：
 *
 * <ol>
 *   <li><strong>口径唯一</strong>。每个组件都必须能追到一个**已登记的 metric_code**
 *       （13.1.1.1 经营总览的 {@code reporting_metric_definition} 是唯一口径表）。
 *       指标码写不进没登记的 —— 那是 DB 层的触发器，不是前端的自觉。</li>
 *   <li><strong>不开放任意 SQL</strong>。维度只有 5 个白名单码，筛选值只作为绑定参数传给服务端。</li>
 *   <li><strong>权限过滤是后端 + DB 层的</strong>。组件集合由服务端按当前用户的权限 / 角色给出
 *       （库里还有一个按同一条规则过滤的视图）—— 前端<strong>不</strong>自己藏组件，
 *       但会把「你看得见几个 / 一共几个」如实说出来。</li>
 *   <li><strong>「算不出来」≠「数据是 0」</strong>。分母为 0 的比率显示成「—」并把原因说清楚，
 *       <strong>不是</strong> 0% —— 「压根算不出来」与「算出来是零」在报表里是相反的两件事。</li>
 *   <li><strong>多币种绝不相加</strong>。一个维度取值下的多个币种分行显示。</li>
 * </ol>
 */
export default function BoardPage() {
  return <BoardWorkspace />;
}
