import { DeferredSettlementWorkspace } from '@/components/finance/deferred-settlement-workspace';

/**
 * 12.3.1.3 延迟结算。
 *
 * <p>蓝图 12-finance.md：「管理平台延迟结算（部分平台按周期后结算或滚动结算）：单列在途未结算金额、
 * 结算条件、预计到账时间，支持查询「订单已发货但钱未到账」的明细。」输入：「结算规则；订单与结算状态。」
 * 输出：「延迟结算明细 + 在途资金统计 + 预计到账时间。」关系：「下游 = 13 利润（按已结算/未结算区分口径）。」
 *
 * <p>这是一个**真实工作区**：延迟结算台账列表（真分页 + 真筛选 + 真排序）+ 在途资金统计
 * （**两套口径同时给出**：订单口径 / 已结算口径 / 在途未结算，按币种分组）
 * + 结算规则登记（店铺级优先、平台级默认兜底）+ 扫描上游已发货订单入台账
 * + 台账作废，加载 / 空 / 错误三态齐全，**没有占位数据**。
 *
 * <p>路由取 {@code /finance/deferred-settlement} —— 与同服务的 12.3.1.1（{@code /finance/settlement}）、
 * 12.3.1.2（{@code /finance/transactions}）**并列**而非嵌套，首 token 也刻意不同：
 * 延迟结算是**新的一层**（在途未结算 / 结算条件 / 预计到账时间），
 * 塞进结算单的命名空间会让「哪张是结算单、哪张是延迟结算台账」变得说不清。
 *
 * <p>页面<b>刻意没有</b>三个入口：「跑对账任务」（12.3.2.1 未交付）、
 * 「看利润」（13 未交付）、「同步平台结算报告」（上游「卷 02 同步」未交付该端点）；
 * 能力声明会如实把它们报成 {@code available=false} 并写明归属模块。
 */
export default function DeferredSettlementPage() {
  return <DeferredSettlementWorkspace />;
}
