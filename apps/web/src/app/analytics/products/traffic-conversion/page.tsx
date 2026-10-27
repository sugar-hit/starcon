import { AnalyticsTrafficConversionWorkspace } from '@/components/analytics/traffic-conversion-workspace';

/**
 * 13.3.2.1 流量转化（卷 13「利润与经营分析」L3 13.3.2 流量与转化 → 13.3.2 下的第一格）。
 *
 * <p>蓝图原文：「分析流量与转化：曝光、点击、Session、转化率、客单价，区分自然与广告流量，
 * 计算转化漏斗。」输入「平台流量数据同步；订单数据。」输出「漏斗视图 + 转化率趋势 + 异常诊断。」
 * 关系：上游 = 2 同步、14 广告；下游 = 13.3.1.1。
 *
 * <p><p>🔴 页面顶部那段话不是提示语，是这一页的设计前提：
 *
 * <ol>
 *   <li><strong>平台流量数据与内部订单数据口径不同</strong>，展示时<strong>必须标注来源与统计范围</strong>
 *       （页面 vs 订单）。所以这一页把<strong>平台口径</strong>与<strong>订单口径</strong>
 *       <strong>分列</strong>：转化率有两个口径、客单价有三个口径，每个值旁边都贴着口径标签。</li>
 *   <li><strong>自然流量是推导值</strong>（平台商品口径 − 广告口径）—— 平台没有提供按流量来源
 *       拆分的流量数据，所以这一页写明它是残余推导值；相减为负时显示「口径冲突」，
 *       <strong>不显示 0</strong>（截成 0 会把口径问题藏起来）。</li>
 *   <li><strong>「算不出来」与「等于 0」分开说</strong>。分母缺失、分母为 0、事实不存在，
 *       一律显示为「不可比 / 没有数据」，<strong>不用 0 冒充</strong> ——
 *       假 0 会被读成「这段时间没流量」。</li>
 *   <li><strong>异常诊断要有依据</strong>。每条诊断都给出观测值、基线值与阈值；
 *       窗口的第一个桶没有基线，<strong>不诊断</strong>（不是「没有异常」）。</li>
 *   <li><strong>数据更新时间常驻</strong>。卷 13 卷首加粗句「数据延迟要显示」。</li>
 * </ol>
 */
export default function AnalyticsProductsTrafficConversionPage() {
  return <AnalyticsTrafficConversionWorkspace />;
}
