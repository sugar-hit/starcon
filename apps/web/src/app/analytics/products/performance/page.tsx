import { AnalyticsProductPerformanceWorkspace } from '@/components/analytics/product-performance-workspace';

/**
 * 13.3.1.1 产品表现分析（卷 13「利润与经营分析」L3 13.3 产品分析下的第一格）。
 *
 * <p>蓝图原文：「单产品的 360 度分析：销量、销售额、毛利、流量、转化、退货、库存、库龄、
 * 广告表现、竞品对比，集成在同一页面滚动查看（避免页面来回跳）。」输入「产品标识
 * （SKU/SPU/Listing）；时间范围；对比基线。」输出「多指标分析页 + 趋势 + 排名分位。」
 * 关系：上游 = 全部业务域；下游 = 各业务域的处理页。**这是运营每天用得最多的页面**，
 * 性能与信息密度优先，指标要可对比（环比/同层级排名）。
 *
 * <p><p>🔴 页面顶部那段话不是提示语，是这一页的设计前提：
 *
 * <ol>
 *   <li><strong>集成在同一页面滚动查看</strong> —— 蓝图原话就是「避免页面来回跳」。
 *       所以后端<b>一次调用</b>返回全部九大区块 + 趋势 + 排名分位，
 *       页面不做瀑布式串行请求，也不在区块之间来回跳。</li>
 *   <li><strong>「算不出来」与「等于 0」分开说</strong>。分母为 0、没有事实、
 *       上游未交付是三件事，分别显示「不可比」「没有数据」「本轮不可用（归谁管）」，
 *       <strong>绝不用 0 冒充</strong> —— 假 0 会被读成「这个商品卖不出去」。</li>
 *   <li><strong>多币种绝不相加</strong>。金额按币种分别列出；只有件数与次数
 *       （与货币无关）给一个跨币种合计，它的币种显示为「与币种无关」。</li>
 *   <li><strong>指标要可对比</strong>。每个指标带环比（变化量 / 变化率）与同层级排名分位，
 *       <strong>由服务端算</strong>（前端只做展示格式化，不做业务算术）。</li>
 *   <li><strong>数据更新时间常驻</strong>。卷 13 卷首加粗句「数据延迟要显示」——
 *       加载中与出错时也在（没投影过就写「尚未投影任何数据」）。</li>
 * </ol>
 */
export default function AnalyticsProductsPerformancePage() {
  return <AnalyticsProductPerformanceWorkspace />;
}
