import { OrderInterceptWorkspace } from '@/components/orders/order-intercept-workspace';

/**
 * 9.2.1.3 异常拦截。
 *
 * <p>蓝图 09 卷：「识别并拦截异常订单：地址不可达/信息不全、黑名单买家、重复下单、
 * 异常折扣（低于成本）、疑似欺诈、超出收货国家限制、平台合规风险。
 * 输出：拦截清单 + 拦截原因 + 处置建议（取消/转人工/放行）。」
 * 蓝图加粗句：「<b>拦截不自动取消</b>，默认转人工——误拦真实订单的代价通常高于人工看一眼。」
 *
 * <p>这是一个真实工作区而不是占位页：拦截策略、限制清单、SKU 成本基线、
 * 拦截清单与八项命中明细、人工处置留痕全部落在 PostgreSQL 16 上，
 * 加载态 / 空态 / 错误态三态齐全。
 *
 * <p>路由取 {@code /orders/review/intercept}，落在同 L3 的 9.2.1.x 命名空间之下，
 * 与 9.2.1.2 审单规则（{@code /orders/review/rules}）并列。
 *
 * <p>刻意**不占用** {@code /orders/review} 本身 —— 它是同 L3 的 9.2.1.1 待审队列的路由
 * （已在 {@code packages/ui/src/lib/task-pages.ts} 登记为订单审核入口），
 * 也不占用 {@code /orders/risk-validation}（9.2.2.2 风控校验）。
 */
export default function OrderInterceptPage() {
  return <OrderInterceptWorkspace />;
}
