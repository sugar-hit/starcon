import { PaymentOrderWorkspace } from '@/components/finance/payment-order-workspace';

/**
 * 12.2.2.1 付款单与审批。
 *
 * <p>蓝图 12-finance.md：「<b>发起付款申请：选择应付明细、付款账户、币种与汇率、付款方式、
 * 分批安排，走分级审批。</b>输入：付款申请；审批规则（金额阈值）；汇率快照。
 * 输出：<b>付款单（草稿/待审/已批/执行中/已付/失败）+ 审批记录</b>。
 * 关系：上游 = 12.1.2.1、12.1.2.2；下游 = 12.2.2.2、17 审批。
 * <b>付款审批是资金安全的第一道控制</b>，大额付款必须双人复核或多级审批。」
 *
 * <p>这是一个**真实工作区**：发起付款申请（读应付明细 → 勾选 → 币种与汇率 → 分批）+
 * 付款单表（真分页 / 真筛选 / 按币种分开汇总）+ 付款单详情（明细 / 分批 / 审批记录 / 事件流）+
 * 提交审批 + 同步审批结果，加载 / 空 / 错误三态齐全，**没有占位数据**。
 *
 * <p>路由取 {@code /finance/payment-orders} —— 与 12.1.2.1 的 {@code /finance/payables}、
 * 12.1.2.2 的 {@code /finance/payment-plans} **并列**而非嵌套：
 * 三个模块的页面各自独立，前端一眼能分清「单据」「计划」「付款申请」。
 *
 * <p>页面<b>刻意没有</b>「执行付款」「标记已付」两个入口：付款属 12.2.2.2「付款执行」
 * （已交付，端点在 {@code /finance/payment-executions}），本页面只做申请与审批；
 * 同样<b>没有</b>本地的「审批通过 / 审批驳回」入口：审批属 17 审批（identity），
 * 审批动作要去 identity 的待我审批里做。
 */
export default function FinancePaymentOrdersPage() {
  return <PaymentOrderWorkspace />;
}
