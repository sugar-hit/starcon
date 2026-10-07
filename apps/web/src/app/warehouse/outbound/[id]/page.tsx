import { OutboundOrderDetailWorkspace } from '@/components/inventory/outbound-order-detail';

/**
 * 8.3.1.2 出库单详情。
 *
 * <p>蓝图：「单出库单作业视图：明细行与已拣/已发量、关联销售订单与买家地址、预占引用、
 * 包裹列表、作业时间线。」输出：「详情 + 作业操作入口。」以及本模块被点名的那条关系：
 * 「**详情页串联整条出库链路**，作业人员不需要在多个页面跳。」
 *
 * <p>这一屏是真实工作区而不是占位页：明细行与件数真的从库里的计划快照现算，作业时间线
 * 真的按 append-only 的留痕表按时间顺序读出，作业操作入口直接来自后端那份转换矩阵。
 *
 * <p>已拣量与已发量**没有真值时显示归属模块而不是 0** —— 8.3.2.1 与 8.3.4.1 尚未交付，
 * 填 0 会让作业员以为一件都没拣。关联销售订单、买家地址、预占引用、包裹列表四段同理：
 * 画成灰块并写清归哪个模块，而不是画成空列表冒充「这单没有」。
 */
export default async function OutboundOrderDetailPage({
  params,
}: {
  params: Promise<{ id: string }>;
}) {
  const { id } = await params;
  return <OutboundOrderDetailWorkspace orderId={id} />;
}