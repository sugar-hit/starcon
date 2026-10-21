import { PurchaseOrderEditWorkspace } from '@/components/purchase/purchase-order-edit-workspace';

/**
 * 6.3.1.2 编辑采购单。
 *
 * <p>路由在 {@code /purchase/orders/{id}/edit}（卷 06 采购与供应）；后端命名空间按服务走，
 * 是 {@code /trade/purchase-order-editor}。
 *
 * <p>🔴 页面要让人看出**能不能改**：已提交的采购单不可直接改数量与单价（6.3.1.3 口径），
 * 所以不可改时后端会把原因带回来，页面原样显示，而不是把表单灰掉就完事 ——
 * 「为什么不能改」比「不能改」本身更重要。
 */
export default async function EditPurchaseOrderPage({
  params,
}: {
  params: Promise<{ id: string }>;
}) {
  const { id } = await params;
  return <PurchaseOrderEditWorkspace orderId={id} />;
}
