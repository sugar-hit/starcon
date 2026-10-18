import { ExchangeAuthorizationWorkspace } from '@/components/aftersales/exchange-authorization-workspace';

/**
 * 11.3.2.1 换货授权。
 *
 * <p>蓝图 11-aftersales-and-returns.md：「<b>批准换货：确认可换数量与库存（要真实预占替换货）、
 * 生成新出库安排，保留原单追溯关系。</b>」
 * 输出句：「换货授权 + 新发货需求 + 替换货预占。」
 * 加粗句：「<b>换货必须同时预占替换货并等待旧货回寄，否则会出现只发不收的损失。</b>」
 *
 * <p>**为什么这层有独立页面**：本模块的输出是一张**运营每天要逐条处置的工作面**
 * （待批准 / 生效中 / 等旧货回寄 / 已撤销），而不是一段被下游消费的声明 ——
 * 9.3.3.1 新出库与 11.2 退回旧货是它的**下游**，不是它的用户界面。
 *
 * <p>这是**真实工作区**：数据全部来自 {@code GET /trade/aftersales/exchanges} 等 5 个真实端点，
 * 加载 / 空 / 错误三态齐全，不是只有字段名的占位页。
 *
 * <p>⚠️ 页面**刻意不伪造**「已下发新出库单」的显示：新出库（9.3.3.1）与旧货回寄实收
 * （11.2.2.2）都尚未交付，页面按 capabilities 如实写明归属模块。
 */
export default function AftersalesExchangesPage() {
  return <ExchangeAuthorizationWorkspace />;
}
