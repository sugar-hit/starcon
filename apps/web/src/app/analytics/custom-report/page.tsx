import { CustomReportWorkspace } from '@/components/analytics/custom-report-workspace';

/**
 * 13.6.1.1 自定义报表（卷 13「利润与经营分析」L3 13.6.1 下的第一格）。
 *
 * <p>蓝图原文：「业务人员自助构建报表：选择数据源（受控的语义层指标，不是直连库表）、
 * 维度、度量、筛选、图表类型、保存与共享、订阅推送。」输入「报表配置；可用的语义层指标清单。」
 * 输出「报表结果 + 分享链接 + 定时订阅。」关系：上游 = 语义层指标定义；下游 = 13.6.2.1 导出。
 * <strong>只允许基于语义层指标自助，不开放任意 SQL</strong> —— 否则报表口径会分裂成几十个版本，
 * 最终没人知道该信哪个。
 *
 * <p><b>🔴 页面顶部那段话不是提示语，是这一页的设计前提</b>：
 *
 * <ol>
 *   <li><strong>口径唯一</strong>。每个度量都必须能追到一个**已登记的 metric_code**
 *       （13.1.1.1 经营总览的 {@code reporting_metric_definition} 是唯一口径表）。
 *       口径改一处，所有引用它的报表自动跟着改。</li>
 *   <li><strong>不开放任意 SQL</strong>。维度只有 5 个白名单码，筛选值只作为绑定参数传给服务端。
 *       页面上的「语义层指标清单」就是那份受控目录。</li>
 *   <li><strong>「算不出来」≠「数据是 0」</strong>。分母为 0 的比率显示成「—」并把原因说清楚，
 *       <strong>不是</strong> 0% —— 「压根算不出来」与「算出来是零」在报表里是相反的两件事。</li>
 *   <li><strong>多币种绝不相加</strong>。涉币指标按币种分行；与货币无关的量才给一个跨币种合计。</li>
 *   <li><strong>未交付就写未交付</strong>。导出（Excel / CSV / PDF）与邮件 / IM 的实际投递属
 *       13.6.2.1（未交付）⇒ 页面按能力声明原样显示「未交付」，<strong>不提供假按钮</strong>。</li>
 * </ol>
 */
export default function CustomReportPage() {
  return <CustomReportWorkspace />;
}
