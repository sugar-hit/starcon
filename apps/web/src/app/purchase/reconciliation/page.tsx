import { PurchaseReconciliationWorkspace } from '@/components/purchase/purchase-reconciliation-workspace';

/**
 * 6.5.1.1 采购对账。
 *
 * <p>蓝图 06 卷：「按期间汇总应收数量、单价、金额、代扣代缴费用、索赔，生成对账单发给供应商确认，
 * 差异挂起。」加粗句：「<b>对账由采购发起、12 财务执行付款</b>，两侧数据以对账单为交接点，
 * 避免采购擅自确认应付。」
 *
 * <p>页面必须让三件事看得见：
 *
 * <ul>
 *   <li><b>基数从哪来</b>：应收取 6.3.2.2 到货流水、索赔取 11.2.3.3 索赔明细，
 *       页面顶部与「对账基数与口径」分区都写清来源。</li>
 *   <li><b>什么没接线</b>：质检扣量（6.4.2.1 未交付）与 12 财务（服务不存在）
 *       在顶部常驻提示里列出，明细行的质检扣量数量显示「无数据」而不是 0。</li>
 *   <li><b>采购侧不确认应付</b>：汇总写「采购侧建议应付（未确认）」，
 *       页面**不提供**「确认应付 / 付款」入口。</li>
 * </ul>
 *
 * <p>前端路由是 {@code /purchase/reconciliation}（蓝图功能树里 6.5.1 写死的 L3 锚点），
 * 后端命名空间是 {@code /trade/purchase-reconciliations} —— 与 6.3.2.2 前端 /purchase/arrivals ↔
 * 后端 /trade/purchase-arrivals、6.2.1.3 前端 /purchase/demand/plans ↔
 * 后端 /trade/purchase-plans 同一纪律。
 */
export default function PurchaseReconciliationPage() {
  return <PurchaseReconciliationWorkspace />;
}
