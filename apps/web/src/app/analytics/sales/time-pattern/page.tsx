import { AnalyticsTimePatternWorkspace } from '@/components/analytics/time-pattern-workspace';

/**
 * 13.2.1.2 分时与分周模式（卷 13「利润与经营分析」L3 13.2 销量分析下的第二格）。
 *
 * <p>蓝图原文：「分析出单时间分布（按小时、按星期），识别高峰低谷，支持按此调整仓内人力与促销排期。」
 * 输入「订单时间戳（按店铺时区换算）；时段配置。」
 * 输出「时段分布热力图 + 高峰时段排序。」
 * 关系：上游 = 9 订单；下游 = 8.5 作业排班、14 广告（分时投放）。
 * **跨境多时区店铺必须按各自时区统计**，汇总时区不同会造成误判。
 *
 * <p>🔴 页面顶部那段话不是提示语，是这一页的设计前提：
 *
 * <ol>
 *   <li><strong>🔴 时区是命门</strong>。「按小时」= <strong>店铺本地时区</strong>的小时，
 *       「按星期」= <strong>店铺本地时区</strong>的星期几（本地周一 00:00 起）。
 *       时区用 <strong>IANA 标识</strong>（如 {@code America/Los_Angeles}），
 *       <strong>不用固定 UTC 偏移</strong> —— 固定偏移会错过夏令时，本地小时整整差一小时。
 *       店铺时区来自 channel 的<strong>站点</strong>参数；定不了的店铺单列，
 *       <strong>不摊给任何时段</strong>（摊给谁是造假）。</li>
 *   <li><strong>🔴 跨店铺汇总必须说清对齐的是哪一个槽位</strong>。「各店本地 17:00」与
 *       「UTC 17:00」<strong>不是同一批订单</strong>，两个口径<strong>结果不同</strong>。
 *       页面常驻显示当前用的是哪一个、为什么 —— 不显示口径的汇总就是让人误判。</li>
 *   <li><strong>🔴 高峰低谷的判定依据要写出来</strong>。层级是<strong>租户自己标的</strong>，
 *       系统只负责<strong>用阈值核对它</strong>；核对结果与理由都回给页面。
 *       数据驱动的 P90 刻度与租户标的层级是<strong>两回事</strong>，分开显示。</li>
 *   <li><strong>多币种绝不相加</strong>。成交金额按币种分别列出；订单数与销量件数与币种无关，
 *       给一个跨币种合计。</li>
 *   <li><strong>数据更新时间常驻</strong>。卷 13 卷首加粗句「数据延迟要显示」。</li>
 * </ol>
 *
 * <p>与同卷的 13.2.1.1「销量趋势」是一对：那边看<strong>时间轴上</strong>的销量走势，
 * 这一页看<strong>一天里的哪个钟点</strong>与<strong>一周里的哪一天</strong>更旺，
 * 直接服务于 8.5 作业排班与 14 广告的分时投放。
 */
export default function AnalyticsSalesTimePatternPage() {
  return <AnalyticsTimePatternWorkspace />;
}
