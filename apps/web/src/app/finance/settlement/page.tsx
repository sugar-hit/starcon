import { SettlementWorkspace } from '@/components/finance/settlement-workspace';

/**
 * 12.3.1.1 结算单。
 *
 * <p>蓝图 12-finance.md：「管理平台结算单：结算周期、店铺、平台账单汇总（销售额、退款、佣金、FBA 费用、
 * 广告费、赔付、其他调整）、结算金额、到账状态。」输出：「结算单 + 汇总项 + 明细行 + 与我方账的差额。」
 * 加粗句：「<b>平台账单是财务事实的重要外部来源，需保留原始文件与解析版本。</b>」
 *
 * <p>这是一个**真实工作区**：结算单列表与详情（汇总项 / 明细行 / 原始文件与解析版本 / 事件流）
 * + 平台账单导入（CSV / JSON，真解析 + 真入库 + 幂等）+ 手工登记 + 结算周期配置
 * + 登记到账 + 与我方账的差额，加载 / 空 / 错误三态齐全，**没有占位数据**。
 *
 * <p>路由取 {@code /finance/settlement} —— 与同服务的 12.1.1.1（{@code /finance/receivables}）、
 * 12.2.1.1（{@code /finance/receipts}）**并列**而非嵌套：
 * 三个模块各有各的单据、单据号前缀与金额口径，混在一个页面里会让「哪张单是应收、哪张是收款、
 * 哪张是结算」变得说不清。
 *
 * <p>页面<b>刻意没有</b>三个入口：
 * 「用平台结算报告自动同步建单」（上游「卷 02 同步」未交付该端点）、
 * 「跑对账任务」（12.3.2.1 未交付）、「看利润」（13 未交付）；
 * 能力声明会如实把它们报成 {@code available=false} 并写明归属模块。
 */
export default function SettlementPage() {
  return <SettlementWorkspace />;
}
