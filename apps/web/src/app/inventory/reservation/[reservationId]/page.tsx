import { InventoryReservationAllocationWorkspace } from '@/components/inventory/inventory-reservation-allocation';

/**
 * 7.2.1.2 预占分配明细。
 *
 * <p>蓝图：「展示一个预占需求实际分配到了哪些 bucket（哪个仓、哪个货位、哪个批次），以及每个分配的
 * held→终态迁移记录。」输出「分配明细行 + 状态迁移时间线」。
 *
 * <p>路由挂在 {@code /inventory/reservation/[reservationId]} 之下 ——
 * 蓝图 07 卷的 L3 锚点路由是 {@code /inventory/reservation}，明细页是它的下级页面；
 * 与需求列表页共用同一个入口前缀，用户从列表点进去不会觉得跳到了另一个系统。
 *
 * <p>页面上四个数字口径，任何一个读错都会导致误判：
 *
 * <ul>
 *   <li>**未分配量 = 需求量 − 已分配量**（还欠多少没圈到 bucket），与
 *       **剩余量 = 需求量 − 已消费量**（还欠多少没出库）是两个问题，分列不合并。
 *   <li>**bucket 数与分配笔数分开**。同一个 bucket 分两次圈只算一个 bucket，
 *       只报笔数会让「圈了几个地方」虚高一倍。
 *   <li>**汇总不随筛选与分页变化**，旁边写明，否则「切了个筛选怎么总量变了」像数据丢了。
 *   <li>**没有释放按钮**。释放与过期属 7.2.1.3，未交付；页面按 capabilities 如实说明去向，
 *       而不是放一个点下去必然 404 的按钮。
 * </ul>
 *
 * <p>蓝图同时明确「承诺分配（committed）后不再自动 TTL 释放」，
 * 所以「已承诺」不是终态，界面上也不会把它显示成「已结束」。
 */
export default async function InventoryReservationAllocationPage({
  params,
}: {
  params: Promise<{ reservationId: string }>;
}) {
  const resolvedParams = await params;
  return <InventoryReservationAllocationWorkspace reservationId={resolvedParams.reservationId} />;
}
