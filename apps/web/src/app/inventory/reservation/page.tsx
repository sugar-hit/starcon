import { InventoryReservationWorkspace } from '@/components/inventory/inventory-reservation';

/**
 * 7.2.1.1 预占需求列表。
 *
 * <p>蓝图：「展示所有预占需求（来自订单履约、加工领料、其他占用），含需求来源、数量、
 * 状态（held/committed/consumed/released/expired）、剩余量、超时时长。」输出「预占需求列表
 * + 状态汇总」。
 *
 * <p>页面上四个数字口径，任何一个读错都会导致误判：
 *
 * <ul>
 *   <li>**剩余量 = 需求量 − 已消费量**，不含「已分配但未出库」的部分 ——
 *       那部分货还在仓里占着可用量，算进来会让列表显示成「这笔已经没事了」。
 *   <li>**超时长为 null 显示「—」而不是 0**。null 是「没超时 / 已终态 / 没有过期时间」，
 *       0 是「超时 0 秒」，两者在界面上完全不同。
 *   <li>**合计不随筛选与分页变化**。拿筛选后的数当合计会让「切了个筛选怎么总量变了」
 *       看起来像数据丢了。
 *   <li>**已分配量恒 0 是因为 7.2.1.2 未交付**，界面上如实标注，不冒充「还没分配」。
 * </ul>
 *
 * <p>本页**只读**且**没有释放入口**：释放与过期属 7.2.1.3，未交付。
 * 蓝图同时明确「承诺分配（committed）后不再自动 TTL 释放」，所以「已承诺」的预占
 * 不计入超时候选 —— 否则清理任务会把已承诺发货的货误释放，直接造成超卖。
 */
export default function InventoryReservationPage() {
  return <InventoryReservationWorkspace />;
}
