import { AnalyticsProductSalesWorkspace } from '@/components/analytics/product-sales-workspace';

/**
 * 13.2.2.2 SKU/SPU/Listing（卷 13「利润与经营分析」L3 13.2 销量分析 → 13.2.2 维度拆解）。
 *
 * <p>蓝图原文：「按商品维度拆解销量：SKU/SPU/Listing 三层，支持 SPU 汇总（多平台多 SKU 合并看）、
 * 变体销量分布。」输入「商品维度；时间范围；是否合并 SPU。」输出「商品销量报表 + 层级钻取。」
 * 关系：上游 = 9 订单、3 商品主数据；下游 = 6 补货、5 定价。
 *
 * <p><p>🔴 页面顶部那段话不是提示语，是这一页的设计前提：
 *
 * <ol>
 *   <li><strong>SPU 口径是跨平台经营的基本单位</strong> —— 只按平台 SKU 看会误判商品整体表现。
 *       所以这一页的 SPU 层把名下所有平台、所有 SKU 合并成一行，并在行上写明合并了多少
 *       SKU × 平台 × Listing。</li>
 *   <li><strong>「算不出来」与「等于 0」分开说</strong>。SPU 销量为 0 时，变体销量占比是
 *       <strong>不可比</strong>，<strong>不是 0%</strong> —— 假 0 会被读成「这个变体卖不出去」。</li>
 *   <li><strong>多币种绝不相加</strong>。成交金额按币种分别列出；只有件数与单数（与货币无关）
 *       给一个跨币种合计，它的币种显示为「与币种无关」。</li>
 *   <li><strong>Listing 层的销量有归属边界</strong>。同一款内部 SKU 挂在多个 Listing 上时，
 *       它的销量归不了任何 Listing —— 这一部分单列成「无法唯一归属」，
 *       <strong>不让用户以为「这个 Listing 就是没卖出去」</strong>。</li>
 *   <li><strong>每个汇总数字都能钻到构成明细</strong> —— 哪张订单的哪一行、哪个 SKU 贡献了它。</li>
 *   <li><strong>数据更新时间常驻</strong>。卷 13 卷首加粗句「数据延迟要显示」。</li>
 * </ol>
 */
export default function AnalyticsSalesProductsPage() {
  return <AnalyticsProductSalesWorkspace />;
}
