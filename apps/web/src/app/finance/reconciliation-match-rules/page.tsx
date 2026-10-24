import { ReconciliationMatchRuleWorkspace } from '@/components/finance/reconciliation-match-rule-workspace';

/**
 * 12.3.2.3 自动匹配规则。
 *
 * <p>蓝图 12-finance.md：「配置对账自动匹配规则：匹配键（订单号 + 金额 + 日期 + 类型）、
 * 容差（汇率差、手续费）、优先级、模糊匹配兜底，配置匹配率目标与监控。」输入：「规则配置；
 * 历史对账结果用于规则调优。」输出：「规则集 + 匹配率统计 + 未匹配原因分布。」
 *
 * <p>这是一个**真实工作区**：规则集（真分页 + 真筛选 + 真搜索）+ 建 / 改规则（匹配键、
 * 至少对上的维度数、日期窗口、三种容差、优先级、模糊兜底、匹配率目标、适用范围）+
 * 启用 / 停用 + 匹配率监控（**按币种分组**）+ 未匹配原因分布 + 匹配率快照历史 +
 * 规则事件流，加载 / 空 / 错误三态齐全，**没有占位数据**。
 *
 * <p>🔴 页面上的匹配键**没有「金额」这一档**：卷 12 硬约束「金额相同即匹配」——
 * 金额只作为容差（金额 / 手续费 / 汇率差）出现，**不计入维度个数**。
 *
 * <p>路由取 {@code /finance/reconciliation-match-rules} —— 与 12.3.2.1 对账任务
 * （{@code /finance/reconciliation-tasks}）、12.3.1.1 结算单（{@code /finance/settlement}）、
 * 12.7.1.2 银行对账（{@code /finance/bank-reconciliation}）**并列**而非嵌套：
 * 那些是别的模块的页面（别的命名空间、别的端点、别的写入所有权）。
 *
 * <p>⚠️ 本页**刻意没有**在 {@code packages/ui/src/lib/task-pages.ts} 登记导航 ——
 * 那个文件是四条车道并发修改的共享文件，并入时必然冲突，留给调度者统一处理。
 */
export default function ReconciliationMatchRulesPage() {
  return <ReconciliationMatchRuleWorkspace />;
}
