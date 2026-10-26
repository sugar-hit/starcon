import { AnalyticsSalesTrendWorkspace } from '@/components/analytics/sales-trend-workspace';

/**
 * 13.2.1.1 销量趋势（卷 13「利润与经营分析」L3 13.2 销量分析下的第一格）。
 *
 * <p>蓝图原文：「销量与销售额趋势：按日/周/月、小时维度查看，支持多维对比
 * （同比/环比/店铺间/产品间）、异常日期标注（大促、缺货、断货）。
 * 输入「时间范围；维度与对比方式；异常事件标注。」输出「趋势图 + 对比视图 + 异常标注。」
 * 关系：上游 = 9 订单数据；下游 = 13.2.2、6.2.1 补货（销量预测输入）。
 * **销量异常必须能关联事件**（大促、断货、价格调整），否则图表只是曲线没有洞察。」
 *
 * <p>🔴 页面顶部那段话不是提示语，是这一页的设计前提：
 *
 * <ol>
 *   <li><strong>「算不出来」与「等于 0」分开说</strong>。基期缺失、基期为 0 时，
 *       增长率是<strong>不可比</strong>，<strong>不是 0%</strong> —— 假 0 会被读成
 *       「这天卖了 0」。没有事实的那天<strong>没有点</strong>，也不是 0。</li>
 *   <li><strong>异常必须能关联事件</strong>（蓝图关系句加粗要求）。异常点带事件清单；
 *       查不到原因时服务端明说「<strong>查不到原因</strong>」—— 页面照原样显示，
 *       不自己编一个理由。</li>
 *   <li><strong>多币种绝不相加</strong>。每个币种各自一条序列，界面上永远没有跨币种的「总计」；
 *       给了本位币也要把<strong>汇率与时点</strong>一起显示。</li>
 *   <li><strong>时区要显示</strong>。跨时区店铺汇总按调用方指定的时区分桶，
 *       响应里显示所用时区 —— 不假装每个店铺都按自己的时区统计过。</li>
 *   <li><strong>数据更新时间常驻</strong>。卷 13 卷首加粗句「数据延迟要显示」。</li>
 * </ol>
 *
 * <p>与同卷的 13.1.1.2「多店对比」是一对：那边看<strong>店铺之间</strong>的横向对比，
 * 这一页看<strong>时间轴上</strong>的销量走势与异常归因。
 */
export default function AnalyticsSalesPage() {
  return <AnalyticsSalesTrendWorkspace />;
}
