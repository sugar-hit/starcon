import { OrderRiskWorkspace } from '@/components/orders/order-risk-workspace';

/**
 * 9.2.2.2 风控校验。
 *
 * <p>蓝图 09 卷：「多信号风险打分：买家历史（退款率、纠纷率）、设备与 IP 关联（16）、
 * 下单频次、金额异常、地址集中度；输出风险分与建议动作（放行 / 人工 / 拒发）。」
 * 蓝图加粗句：「风控分数不能单独作为拒绝依据，需人工复核，避免误伤正常复购客户。」
 *
 * <p>这是一个真实工作区而不是占位页：风控规则（可维护）、风险信号登记（租户维护）、
 * 五维打分（三个维度真查订单簇）、人工复核全部落到 PostgreSQL 16，
 * 加载态 / 空态 / 错误态三态齐全。
 *
 * <p>路由取 {@code /orders/risk-validation}，落在蓝图 09 的 L3 锚点 {@code /orders}
 * 之下，与 9.2.1.x 审单（{@code /orders/review}，另一条车道）并列 ——
 * 「算出风险分」与「决定发不发」是两件事，权限与日志都该分得开。
 *
 * <p>刻意**不占用** {@code /orders/review} 及其子路径（9.2.1.x 的地盘），
 * 也不动 {@code /orders/rules}（未分配给任何模块）。
 */
export default function OrderRiskValidationPage() {
  return <OrderRiskWorkspace />;
}
