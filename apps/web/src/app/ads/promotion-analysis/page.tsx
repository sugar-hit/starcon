import { AdPromotionAnalysisWorkspace } from '@/components/ads/ad-promotion-analysis';

/**
 * 14.4.2.1 促销效果分析。
 *
 * <p>蓝图：「促销效果分析：促销期间销量/销售额/毛利变化、增量判断、补贴成本、ROI，与活动前的基期对比。
 * 输入：促销数据；历史基线；利润数据。输出：效果报表 + 增量估算 + ROI。
 * 关系：上游 = 14.4.1；下游 = 13.4 利润、14.2.2.1 预算。
 * <strong>促销期间毛利可能大幅下滑</strong>，评估必须看毛利而不是销售额。」
 *
 * <p>🔴 页面顶部那段话不是提示语，是这一页的设计前提：
 *
 * <ol>
 *   <li><strong>销售额与毛利必须同屏</strong>。毛利不是附注 —— 判定看的是毛利，
 *       两列分开在不同区块就等于把「促销期间毛利大幅下滑」藏起来了。</li>
 *   <li><strong>判定看毛利</strong>。判据是<strong>增量毛利</strong>，不是增量销售额；
 *       另设「放量不赚」列标出销售额涨了但毛利率跌了的活动。</li>
 *   <li><strong>不可比不是 0</strong>。没登记成本快照 ⇒ 毛利显示<strong>不可比</strong>；
 *       促销类型没有可折算价格系数 ⇒ 补贴显示<strong>不可比</strong>。
 *       0 会被读成「毛利是 0」/「这次没花钱」。</li>
 *   <li><strong>跳过不许静默消失</strong>。跑一次分析时没起止时间 / 没有适用商品的促销
 *       会记进运行留痕并在页面上显示出来。</li>
 *   <li><strong>导出留痕可查</strong>。导出是一次动作（谁、什么时候、什么筛选条件、
 *       导出了多少行），CSV 的表头里带着口径。</li>
 * </ol>
 */
export default function AdPromotionAnalysisPage() {
  return <AdPromotionAnalysisWorkspace />;
}
