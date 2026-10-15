'use client';

import { DealerChannelWorkspace } from '@/components/orders/dealer-channel-workspace';

/**
 * 9.7.1.2 经销与分销管理的前端入口 `/orders/dealers`。
 *
 * <p>**刻意不是 `/orders/b2b` 的 tab。** `/orders/b2b` 那一页是 9.7.1.1
 * 批发订单与账期（同一 L3 下的兄弟模块）的页面，而本模块的四件事与它都不同：
 *
 * <ul>
 *   <li>读写边界不同：那里是「下单 → 发货 → 账期应收」的流转；
 *       本模块是「授权 → 考核 → 计提 → 预警」的台账。</li>
 *   <li>主查询维度不同：那里按账期到期日排应收（资金计划取数顺序）；
 *       本模块按授权与考核期间组织。</li>
 * </ul>
 *
 * <p>后端也刻意分成两个命名空间：批发订单 = {@code /trade/b2b-orders}，
 * 经销管理 = {@code /trade/dealers}，两边互不覆盖、互不 JOIN。
 */
export default function DealersPage() {
  return <DealerChannelWorkspace />;
}
