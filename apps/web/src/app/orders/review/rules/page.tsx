import { OrderReviewRuleWorkspace } from '@/components/orders/order-review-rule-workspace';

/**
 * 9.2.1.2 审单规则。
 *
 * <p>蓝图 09 卷：「配置自动审单规则：按店铺/金额/买家等级/商品/收货国家自动通过或转人工；
 * 命中黑名单、地址异常、大额、异常折扣的强制转人工。规则有优先级与生效期。」
 * 输出是「规则命中结果（每单命中的规则链）；规则生效记录」。
 *
 * <p>这是一个真实工作区而不是占位页：规则 CRUD、生效期与优先级判定、买家画像登记、
 * 试跑与真实判定、逐层可解释的命中链全部落到 PostgreSQL 16，
 * 加载态 / 空态 / 错误态三态齐全。
 *
 * <p>路由取 {@code /orders/review/rules}，落在蓝图 09 的 L3 锚点 {@code /orders/review} 之下。
 *
 * <p>刻意**不占用** {@code /orders/review} 本身 —— 它是同 L3 的 9.2.1.1 待审队列的路由
 * （另一条车道，未交付），也不动 {@code /orders/rules}（未分配给任何模块）。
 */
export default function OrdersReviewRulesPage() {
  return <OrderReviewRuleWorkspace />;
}
