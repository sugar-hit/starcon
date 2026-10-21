import { PurchaseRequestWorkspace } from '@/components/purchase/purchase-request-workspace';

/**
 * 6.2.2.1 采购申请。
 *
 * <p>蓝图 06 卷：「把计划行/补货建议转成采购申请，走审批，确认供应商与数量后转采购单。」
 * 输出「采购申请单（草稿/待审/已批/已转单/已驳回）；审批记录」。
 *
 * <p>页面必须让三件事看得见：
 *
 * <ul>
 *   <li><b>申请与采购单分开</b>：6.3.1.2 采购单未交付 ⇒ 页面上出现的是**转单事实**
 *       （供应商 / 数量 / 交期 / 目标仓），**不是**「采购单已创建」。</li>
 *   <li><b>审批同步失败必须显眼</b>：审批服务不可达时申请单**停在待审**，
 *       页面把失败原因原样显示出来 —— 不显示 = 用户以为它还在正常流转。</li>
 *   <li><b>金额算不出来就说算不出来</b>：预估金额显示「无数据」而不是 0。</li>
 * </ul>
 *
 * <p>前端路由是 {@code /purchase/demand/requests}（与 6.2.1.3 的 /purchase/demand/plans 同域不同页），
 * 后端命名空间是 {@code /trade/purchase-requests} —— 与 6.2.1.3 前端 /purchase/demand/plans ↔
 * 后端 /trade/purchase-plans、6.2.1.2 前端 /purchase/demand/replenishment ↔
 * 后端 /trade/replenishment-suggestions 同一纪律。
 */
export default function PurchaseDemandRequestsPage() {
  return <PurchaseRequestWorkspace />;
}
