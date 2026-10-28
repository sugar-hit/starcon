import { AnalyticsSkuProfitWorkspace } from '@/components/analytics/sku-profit-workspace';

/**
 * 13.4.1.2 SKU 利润（卷 13「利润与经营分析」L3 13.4.1 利润报表下的第二格）。
 *
 * <p>蓝图原文：「SKU 级利润明细：单品收入、采购成本、头程、平台费、物流费、广告费、
 * 退款损失、净利，支持查看构成瀑布与订单级明细钻取。」输入「SKU 维度；时间范围；
 * 成本分摊结果。」输出「SKU 利润表 + 瀑布图 + 订单明细钻取。」关系：上游 = 13.4.1.1、
 * 12.4.2.2；下游 = 5 定价、6 采购。
 *
 * <p>🔴 页面顶部那段话不是提示语，是这一页的设计前提：
 *
 * <ol>
 *   <li><strong>「算不出来」与「等于 0」分开说</strong>。某一类成本在 SKU 维度上拿不到时它是
 *       <strong>—</strong>，净利整格是<strong>—（不可比）</strong>，**不是 0.00** ——
 *       假 0 会被读成「完全没花钱」。</li>
 *   <li><strong>「不可归属到 SKU」与「缺这一项」是两件事</strong>。前者是上游只分摊到
 *       店铺 / 订单（本模块<strong>不重算分摊</strong>），后者是上游在 SKU 维度上有、
 *       这个 SKU 没有。两者都让净利不可比，但原因不同。</li>
 *   <li><strong>口径必须写在报表上</strong>（卷 13 强调）：收入怎么算、各项成本取自哪一路上游、
 *       退款损失怎么算、哪些算不出来 —— 页面顶部与能力声明区都常驻显示。</li>
 *   <li><strong>多币种绝不相加</strong>。每一行带自己的币种；本页不提供跨币种的净利合计。</li>
 *   <li><strong>数据更新时间常驻</strong>。卷 13 卷首加粗句「数据延迟要显示」。</li>
 * </ol>
 *
 * <p>与同 L3 的兄弟模块 13.4.1.1「多模式利润报表」是一对：那边管<strong>按经营模式</strong>的
 * 利润报表与多维核算，这一页管 <strong>SKU 级</strong>的利润明细、构成瀑布与订单钻取。
 */
export default function AnalyticsSkuProfitPage() {
  return <AnalyticsSkuProfitWorkspace />;
}
