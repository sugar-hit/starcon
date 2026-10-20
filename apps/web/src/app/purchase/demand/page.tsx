import { StockoutRiskWorkspace } from '@/components/purchase/stockout-risk-workspace';

/**
 * 6.2.1.1 缺货与断货风险。
 *
 * <p>蓝图 06 卷：「识别断货风险 SKU，输出补货优先级与缺口量。」输入「销售速度（日均销量）、
 * 当前可用库存、在途量、备货周期」。输出「断货风险清单（SKU、可用量、日均销、可支撑天数、
 * 预计断货日、缺口量、涉及店铺），按紧急度排序」。
 *
 * <p>页面必须让两件事看得见：
 *
 * <ul>
 *   <li><b>「无数据」不是 0</b>：没有登记销售速度的 SKU 判不出断货日，
 *       页面显示「无数据」而不是 0 —— 0 日均销量会让可支撑天数变成无穷、
 *       健康度被高估，那正是要防的事。</li>
 *   <li><b>排序依据随结论一起显示</b>：每行的紧急度旁边就是后端给的分档原文，
 *       「为什么这条排在前面」不用点进去再猜。</li>
 * </ul>
 *
 * <p>前端路由是 {@code /purchase/demand}（蓝图功能树 6.2.1 需求生成），
 * 后端命名空间是 {@code /trade/stockout-risk} —— 与 9.3.2.2 前端 /orders ↔
 * 后端 /trade/fulfillment/routing 同一纪律。
 */
export default function PurchaseDemandPage() {
  return <StockoutRiskWorkspace />;
}
