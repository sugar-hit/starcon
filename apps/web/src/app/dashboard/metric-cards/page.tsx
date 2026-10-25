import { MetricCardWorkspace } from '@/components/dashboard/metric-card-workspace';

/**
 * 1.1.2.1 核心指标卡（卷 01「工作台与门户」L3 1.1.2 关键指标下的第一格）。
 *
 * <p>蓝图原文：
 * 「一屏展示今日/昨日/本月的销售额、订单量、销量、库存总额、毛利、回款、在途金额，
 * 每张卡带环比与同比，点击下钻到对应报表。」
 *
 * <p><b>路由说明</b>：本模块在台账里的 L3 锚点路由是「—」（1.1.2 下的三格都还没定锚点路由），
 * 按仓内惯例做成**真实可访问的工作区**，入口挂在工作台区（{@code /dashboard/metric-cards}）。
 * 它<b>不改</b> {@code apps/web/src/app/page.tsx} 首页 —— 首页是人工与别的模块共用的。
 *
 * <p><b>这是一个真实页面而不是占位页</b>：七张卡的数值、环比、同比、趋势箭头、下钻入口
 * 全部来自 reporting 服务的只读投影端点（{@code GET /reporting/metric-cards}），
 * 口径说明逐条来自能力声明端点（{@code GET /reporting/metric-cards/capabilities}），
 * 且页面**没有任何写动作**（卷 01 的红线：工作台只读 + 跳转）。
 *
 * <p>🔴 **前端不重算任何比率**：环比 / 同比的变化率与趋势箭头都是后端按 13 的口径表算好的，
 * 前端只做展示 —— 「基期没数据」显示「—」而不是 0%，未接通的卡片显示灰格而不是 0。
 */
export default function MetricCardsPage() {
  return <MetricCardWorkspace />;
}
