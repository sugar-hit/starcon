import { ExchangeOrderLinkWorkspace } from '@/components/aftersales/exchange-order-link-workspace';

/**
 * 11.3.2.2 换货新单。
 *
 * <p>蓝图 11-aftersales-and-returns.md：「<b>建立换货关联（原售后 ↔ 新销售单/发货单），
 * 跟踪换货闭环状态，防止重复换货。</b>」
 * 输出句：「换货关联记录（exchange_order_link）+ 新单状态跟踪。」
 * 关系句：「<b>换货新单不修改原出库事实，原单保持完整可追溯。</b>」
 *
 * <p>**为什么这层有独立页面**：本模块的输出是一张**运营每天要逐条盯的工作面**
 * （待提交 / 已发出 / 等旧货回寄 / 已闭环），而不是一段被下游消费的声明 ——
 * 9 订单、8 出库是它的**下游**，不是它的用户界面。
 *
 * <p>这是**真实工作区**：数据全部来自 {@code GET /trade/aftersales/exchange-orders}
 * 等 7 个真实端点，加载 / 空 / 错误三态齐全，不是只有字段名的占位页。
 *
 * <p>⚠️ 页面**刻意不伪造**「已发货」：闭环状态只承认真实发货记录
 * （9.4.1.1 的 shipment_record），读不到就一直停在「已建立」并写明还差什么。
 */
export default function AftersalesExchangeOrdersPage() {
  return <ExchangeOrderLinkWorkspace />;
}
