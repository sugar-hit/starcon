import { PeriodCloseWorkspace } from '@/components/finance/period-close-workspace';

/**
 * 12.5.1.3 关账。
 *
 * <p>蓝图 12-finance.md：「期末关账流程：检查未处理事项（未对账差异、未结转成本、未核销款项、
 * 未过账凭证），按已批准清单区分阻断项与可结转未清项后关闭期间；禁止向已关闭期间录入业务凭证。」
 * 输入：「关账检查清单结果；关账确认。」输出：「关账完成记录；期间锁定。」
 * 关系：「上游 = 12.5.1.2；下游 = 12.5.2.2 报表。**关账检查清单是防错最后一道关**：
 * 阻断项未解决不允许关账；正常未到期应收应付不要求清零，例外与结转须保留依据。」
 *
 * <p>这是一个**真实工作区**：跑关账检查清单（四类未处理事项真查数据，逐项给出条数、
 * 分类、依据与可钻取明细）+ 阻断项 / 可结转未清项的分类与例外依据落库 +
 * 提交关账（走 17 审批，四种结局如实显示）+ 关账执行记录（审批链路可钻取），
 * 加载 / 空 / 错误三态齐全，**没有占位数据**。
 *
 * <p>路由取 {@code /finance/period-close} —— 与同服务的其它端点（{@code /finance/settlement}、
 * {@code /finance/vouchers}、{@code /finance/accounting-periods} …）**并列**而非嵌套，
 * 首 token 也刻意不同。
 *
 * <p>页面<b>刻意没有</b>两个入口：「批量关账」（本模块没有批量端点）与
 * 「跳过检查清单直接关账」（那正是这一道关要防的事）—— 界面把这两件事做成不可能。
 */
export default function PeriodClosePage() {
  return <PeriodCloseWorkspace />;
}
