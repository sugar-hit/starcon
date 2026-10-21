import { PurchaseOrderEditWorkspace } from '@/components/purchase/purchase-order-edit-workspace';

/**
 * 6.3.1.2 新建采购单。
 *
 * <p>蓝图：「创建/编辑采购单：选择供应商、添加 SKU 行（带出报价与库存快照）、填写数量/采购单位/
 * 单价/币种、约定交期与发货地址、备注与附件。支持草稿与提交。」
 *
 * <p>路由在 {@code /purchase/orders/new}（卷 06 采购与供应）；后端命名空间按服务走，
 * 是 {@code /trade/purchase-order-editor}（与 6.3.1.1 的 {@code /trade/purchase-orders}
 * 同一纪律，但**分成两个命名空间** —— 两者是同 L3 下的兄弟模块，
 * 各自一个命名空间才能让多车道共用的白名单各自登记而不互相覆盖）。
 *
 * <p>建出来的是**草稿**：提交是另一个动作，提交那一刻才冻结单价、汇率与单位换算的快照。
 */
export default function NewPurchaseOrderPage() {
  return <PurchaseOrderEditWorkspace />;
}
