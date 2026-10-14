'use client';

import { Tabs, TabsContent, TabsList, TabsTrigger } from '@starcon/ui';
import { ManualOrderWorkspace } from '@/components/orders/manual-order-workspace';
import { OrderListWorkspace } from '@/components/orders/order-list-workspace';

/**
 * 9.1.1 订单查询 与 9.1.2 订单来源 —— 两个并列 L4 共用 `/orders` 这一个前端路由。
 *
 * <p>蓝图 09 卷的系统树里，9.1.1（订单查询 → 9.1.1.1 订单列表）与
 * 9.1.2（订单来源 → 9.1.2.2 手工订单）是**并列的两个 L4**，
 * 而两者都指向同一个前端入口 `/orders`。同一个 URL 不能有两个页面，
 * 故本页用 tab 区分，而不是让两个 workspace 互相覆盖：
 *
 * <ul>
 *   <li><b>「订单列表」= 9.1.1.1</b>：全渠道统一订单列表，<b>只读</b>。
 *       蓝图要点名的支付状态 / 履约状态 / 发货仓 / 平台状态 / 售后状态 / 平台店铺
 *       在本服务尚不存在，页面显示「—」并写明归属模块。</li>
 *   <li><b>「手工订单」= 9.1.2.2</b>：非平台来源订单的录入，<b>有写入口</b>。</li>
 * </ul>
 *
 * <p>两个模块<b>读写边界不同</b>，所以做成 tab 而不是合并成一个表格：
 * 订单列表是 sales_order 的读投影（不提供任何写端点），
 * 手工订单是它的写入所有者。合并会让「哪个 tab 能改数据」这件事变得不明显。
 *
 * <p>后端是两个并列命名空间，互不覆盖：
 * 9.1.1.1 = {@code /trade/sales-orders/list}，
 * 9.1.2.2 = {@code /trade/sales-orders}。
 */
export default function OrdersPage() {
  return (
    <Tabs defaultValue="list">
      <TabsList>
        <TabsTrigger value="list">订单列表</TabsTrigger>
        <TabsTrigger value="manual">手工订单</TabsTrigger>
      </TabsList>
      <TabsContent value="list">
        <OrderListWorkspace />
      </TabsContent>
      <TabsContent value="manual">
        <ManualOrderWorkspace />
      </TabsContent>
    </Tabs>
  );
}
