import { OrderAddressPaymentCheckWorkspace } from '@/components/orders/order-address-payment-check-workspace';

/**
 * 9.2.2.1 地址与支付校验。
 *
 * <p>蓝图 09 卷：「校验买家地址可投递性（国家/邮编/街道格式）、禁运国家清单、
 * 支付状态一致性（平台已付/货到付款/未付款）。输出：校验结果 + 风险等级。」
 * 蓝图加粗句：「<b>货到付款订单的履约风险最高</b>，需额外风控与保险策略。」
 *
 * <p>这是一个真实工作区而不是占位页：禁运国家清单、国别地址格式规则、
 * 支付状态一致性规则、校验结果与七项明细全部落在 PostgreSQL 16 上，
 * 加载态 / 空态 / 错误态三态齐全。
 *
 * <p>路由取 {@code /orders/address-payment-check}，与 9.2.1.2 审单规则
 * （{@code /orders/review/rules}）、9.2.1.3 异常拦截（{@code /orders/review/intercept}）、
 * 9.2.2.2 风控校验（{@code /orders/risk-validation}）并列，互不占用。
 */
export default function OrderAddressPaymentCheckPage() {
  return <OrderAddressPaymentCheckWorkspace />;
}
