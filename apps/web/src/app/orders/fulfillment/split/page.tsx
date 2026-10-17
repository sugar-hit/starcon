import { OrderSplitWorkspace } from '@/components/orders/order-split-workspace';

/**
 * 9.3.1.1 拆单。
 *
 * <p>蓝图 09 卷：「把一个订单拆成多个履约单元：按仓库拆、按承运商拆、按发货时效拆（如部分现货先发）、
 * 按平台限制拆。」输出是「多个履约请求 + 库存预占指令 + 发货回传按拆分执行」。
 *
 * <p>这是一个真实工作区而不是占位页：拆单规则、发货仓与可用库存分布、
 * 拆单预览（无副作用）、拆单落库、逐行逐仓的候选依据、预占指令登记与发货回传
 * 全部落到 PostgreSQL 16，加载态 / 空态 / 错误态三态齐全。
 *
 * <p>路由取 {@code /orders/fulfillment/split}，落在蓝图 09 的 L3 锚点
 * {@code /orders/fulfillment} 之下，与 9.3.1.2 的 {@code /fulfillment/consolidation}、
 * 9.3.2.1 的 {@code /fulfillment/allocation}、9.3.2.2 的 {@code /fulfillment/routing}
 * 并列 —— 拆、合、分仓、选履约路径是四件事，权限与日志分得开。
 *
 * <p>刻意**不占用** {@code /orders/fulfillment} 本身（本批另一条车道 9.3.3.1 的），
 * 也不动 {@code /orders}（9.1 订单列表车道）。
 */
export default function OrdersFulfillmentSplitPage() {
  return <OrderSplitWorkspace />;
}
