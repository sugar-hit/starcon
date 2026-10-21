import { PurchaseOrderApprovalWorkspace } from '@/components/purchase/purchase-order-approval-workspace';

/**
 * 6.3.1.3 采购单审批。
 *
 * <p>蓝图 06 卷：「按金额阈值与采购员分级审批，支持批量审批、驳回并附意见，
 * 审批通过后锁定单据生成应付。」输出「审批结果 + 审批链路记录；通过后触发 12 生成应付、8 允许收货」。
 *
 * <p>页面必须让四条硬要求看得见：
 *
 * <ul>
 *   <li><b>不能批要说清为什么</b>：层级不够 / 金额超授权 / 不能自审 / 已经批过，
 *       每一行都带原因 —— 只给一个灰按钮会让人以为系统坏了。</li>
 *   <li><b>「还没登记应付」不是 0</b>：应付登记与收货放行用状态表达，
 *       金额显示「无数据」。</li>
 *   <li><b>待审金额按币种分列</b>：页面不出现一个跨币种的合计数。</li>
 *   <li><b>未交付的下游说清</b>：12 财务 / 08 仓储 / 17 组织的状态写在能力边界区，
 *       不给一个点下去 404 的按钮。</li>
 * </ul>
 *
 * <p>路由在 {@code /purchase/approvals}（卷 06 采购与供应）；后端命名空间按服务走，
 * 是 {@code /trade/purchase-order-approvals}（与 6.3.1.1 {@code /trade/purchase-orders}、
 * 6.3.1.2 {@code /trade/purchase-order-editor} 同一纪律：三个兄弟各自一个首段）。
 */
export default function PurchaseApprovalsPage() {
  return <PurchaseOrderApprovalWorkspace />;
}
