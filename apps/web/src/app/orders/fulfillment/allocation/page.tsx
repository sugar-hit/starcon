import { WarehouseAllocationWorkspace } from '@/components/orders/warehouse-allocation-workspace';

/**
 * 9.3.2.1 分仓规则。
 *
 * <p>蓝图 09 卷：「配置并执行仓库分配：按库存可用性、运费成本、时效承诺、货权归属、
 * 买家地区匹配度选择发货仓；支持优先级与兜底策略。」输出是
 * 「分配结果（发货仓 + 分配数量）」与「无法分配的异常挂起」。
 *
 * <p>这是一个真实工作区而不是占位页：仓库档案（含货权法人）、分仓规则、试算、
 * 执行分配、分配详情与挂起清单全部落到 PostgreSQL 16，
 * 加载态 / 空态 / 错误态三态齐全。
 *
 * <p>路由取 {@code /orders/fulfillment/allocation}，与 9.3.2.2 的
 * {@code /orders/fulfillment/routing} 并列 —— 两者是同一条履约链上相邻的两环
 * （选履约路径 vs 选发货仓）。
 *
 * <p>刻意**不占用** {@code /orders/fulfillment} 本身：那个路由在 task-pages 里
 * 已登记给 9.3.3.1 待发货订单（另一条车道），两条车道各写一个 page.tsx 会互相覆盖。
 * 同理刻意**不改** {@code /orders} 与 {@code /orders/fulfillment/routing}。
 */
export default function OrdersFulfillmentAllocationPage() {
  return <WarehouseAllocationWorkspace />;
}
