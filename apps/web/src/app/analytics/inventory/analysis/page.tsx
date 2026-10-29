import { AnalyticsInventoryAnalysisWorkspace } from '@/components/analytics/inventory-analysis-workspace';

/**
 * 13.5.1.1 库存分析（卷 13「利润与经营分析」L3 13.5.1 库存分析下的第一格）。
 *
 * <p>蓝图原文：「库存分析报表：库存金额结构、周转率、库龄分布、缺货与超储、ABC 分类、呆滞占比，
 * 按仓库/类目/店铺拆解。」输入「库存数据（7）；销售数据；盘点历史。」
 * 输出「库存分析报表 + 图表 + 下钻。」
 *
 * <p>🔴 页面顶部那段话不是提示语，是这一页的设计前提：
 *
 * <ol>
 *   <li><strong>周转率按成本口径算</strong>。分子是**销售出库成本**，分母是**成本口径的库存金额** ——
 *       用售价算会虚高（蓝图加粗句）。页面常驻服务端口径说明。</li>
 *   <li><strong>「算不出来」与「数值是 0」分开说</strong>。没有维护成本的 SKU 金额是「—」，
 *       分母为 0 的周转率是「—（不可比）」，**不是 0**。</li>
 *   <li><strong>多币种绝不相加</strong>。跨币种的合计不存在。</li>
 *   <li><strong>数据更新时间常驻</strong>（卷 13 卷首加粗句「数据延迟要显示」）。</li>
 *   <li><strong>「哪些确实拿不到」写在页面上</strong>（能力声明），而不是让用户以为每一格都有数。</li>
 * </ol>
 *
 * <p>与同卷的 13.4.x「利润拆解」是不同的视角：那边管**钱赚在哪里**，
 * 这一页管**货压在哪里、多久没动、哪些要补、哪些要清**。
 */
export default function AnalyticsInventoryAnalysisPage() {
  return <AnalyticsInventoryAnalysisWorkspace />;
}
