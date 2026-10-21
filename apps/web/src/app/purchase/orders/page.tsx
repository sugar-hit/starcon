import { PurchaseOrderListWorkspace } from '@/components/purchase/purchase-order-list-workspace';

/**
 * 6.3.1.1 采购单列表。
 *
 * <p>蓝图 06 卷：「采购单主列表：单号、供应商、SKU 行数、总金额、币种、状态、承诺到货日、
 * 已收数量/比例、逾期标记，支持多维筛选与导出。」输出「分页列表 + 批量操作（催单、关闭、导出）」。
 *
 * <p>页面必须让三条硬要求看得见：
 *
 * <ul>
 *   <li><b>「未跟踪」与「已收 0」分开</b>：没进 6.3.2.2 到货跟踪的单显示「未跟踪」，
 *       绝不显示 0 —— 「还没登记到货」与「一件都没到」对采购员的动作含义相反。</li>
 *   <li><b>总金额按币种分列</b>，页面不出现一个跨币种的合计数；
 *       本位币合计取不到时显示「无数据」并把排除原因原样透出。</li>
 *   <li><b>数据范围常驻</b>：当前生效的范围（ALL / DEPARTMENT / SELF）与范围说明一直显示，
 *       采购员要能知道「我为什么只看到这几张单」。</li>
 * </ul>
 *
 * <p>路由在 {@code /purchase/orders}（卷 06 采购与供应）；后端命名空间按服务走，
 * 是 {@code /trade/purchase-orders}（与 6.3.2.2 到货跟踪 {@code /trade/purchase-arrivals}
 * 同一纪律）。
 */
export default function PurchaseOrdersPage() {
  return <PurchaseOrderListWorkspace />;
}
