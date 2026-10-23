import { TransactionWorkspace } from '@/components/finance/transaction-workspace';

/**
 * 12.3.1.2 交易明细。
 *
 * <p>蓝图 12-finance.md：「管理结算单下的逐笔明细：订单销售、退款、费用（佣金/仓储/配送/广告）、赔付、调整、代扣税，
 * 保留外部流水号与币种；<b>允许无订单行的费用类明细</b>。」输入：「平台交易报告解析结果；<code>idempotency_key</code>。」
 * 输出：「明细行 + <b>汇总校验（明细合计 = 结算单汇总）</b>。」
 *
 * <p>这是一个**真实工作区**：交易明细列表（真分页 + 真筛选 + 真排序 + 按币种/交易类型的汇总）
 * + 登记一条明细（带幂等键，订单行可空）+ 上传平台交易报告（CSV / JSON，真解析 + 真入库 + 逐条告警）
 * + **汇总校验**（明细合计 = 汇总合计 = 结算金额，并逐项核对分类口径 / 笔数 / 币种 / 符号 / 周期），
 * 加载 / 空 / 错误三态齐全，**没有占位数据**。
 *
 * <p>路由取 {@code /finance/transactions} —— 与同服务的 12.1.1.1（{@code /finance/receivables}）、
 * 12.2.1.1（{@code /finance/receipts}）、12.3.1.1（{@code /finance/settlement}）**并列**而非嵌套：
 * 四个模块各有各的单据、单据号前缀与金额口径，混在一个页面里会让「哪张单是应收、哪张是收款、
 * 哪张是结算、哪张是交易明细」变得说不清。
 *
 * <p>页面<b>刻意没有</b>三个入口：
 * 「用平台交易报告自动同步登记明细」（上游「卷 02 同步」未交付该端点）、
 * 「跑对账任务」（12.3.2.1 未交付）、「看利润」（13 未交付）；
 * 能力声明会如实把它们报成 {@code available=false} 并写明归属模块。
 */
export default function TransactionsPage() {
  return <TransactionWorkspace />;
}
