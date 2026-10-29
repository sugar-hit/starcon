import { AnalyticsProcurementAnalysisWorkspace } from '@/components/analytics/procurement-analysis-workspace';

/**
 * 13.5.2.1 采购分析（卷 13「利润与经营分析」L3 13.5.2 采购分析下的第一格）。
 *
 * <p>蓝图原文：「采购分析：采购金额趋势与占比、供应商集中度、价格波动、到货准时率、
 * 采购周期、采购成本占销售额比。」输入「采购数据（6）；供应商评级。」
 * 输出「采购报表 + 趋势 + 供应商对比。」
 *
 * <p>🔴 页面顶部那段话不是提示语，是这一页的设计前提：
 *
 * <ol>
 *   <li><strong>「算不出来」与「数值是 0」分开说</strong>。没有金额的到货流水是「—」，
 *       分母为 0 的准时率与成本占比是「—（暂无数据）」，<strong>不是 0</strong>。</li>
 *   <li><strong>供应商集中度带风险信号</strong>。蓝图加粗句：「供应商集中度是供应链风险指标，
 *       单一供应商占比过高会在断供时直接击穿业务。」页面给的是风险等级 + 风险说明
 *       （哪一家、占比多少、断供时哪一档采购额会中断），不是一个中性的百分比。</li>
 *   <li><strong>多币种绝不相加</strong>。跨币种的合计不存在。</li>
 *   <li><strong>数据更新时间常驻</strong>（卷 13 卷首加粗句「数据延迟要显示」）。</li>
 *   <li><strong>「哪些确实拿不到」写在页面上</strong>（能力声明），并给出归属模块号。</li>
 * </ol>
 *
 * <p>与同卷的 13.5.1.1「库存分析」是不同的视角：那边管**货压在哪里、多久没动**，
 * 这一页管**钱花在哪一家供应商身上、断供时会不会被打穿**。
 */
export default function AnalyticsProcurementAnalysisPage() {
  return <AnalyticsProcurementAnalysisWorkspace />;
}
