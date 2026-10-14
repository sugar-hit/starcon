'use client';

import { B2BOrderWorkspace } from '@/components/orders/b2b-order-workspace';

/**
 * 9.7.1.1 批发订单与账期的前端入口 `/orders/b2b`。
 *
 * <p>**刻意不是 `/orders` 的第三个 tab。** `/orders` 那一页是 9.1.1.1 订单列表与
 * 9.1.2.2 手工订单共用的 tab 容器，属于**别的模块**的页面；而本模块的读写语义与
 * 它们都不同：
 *
 * <ul>
 *   <li>读写边界不同：那里一个是只读投影、一个是零售录入；本模块是 B2B 录入 +
 *       发货确认 + 账期应收，读写都不同。</li>
 *   <li>主查询维度不同：那里按创建时间排订单；本模块的账期应收按**到期日**排，
 *       那是资金计划（12.1.2.2）取数的顺序。</li>
 * </ul>
 *
 * <p>后端也刻意分成两个命名空间：零售 = {@code /trade/sales-orders}，
 * 批发 = {@code /trade/b2b-orders}，两边互不覆盖、互不 JOIN。
 */
export default function B2BOrdersPage() {
  return <B2BOrderWorkspace />;
}
