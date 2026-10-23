import { ReturnInventoryDispositionWorkspace } from '@/components/aftersales/return-disposition-workspace';

/**
 * 11.2.3.2 转可售/残次/报废。
 *
 * <p>蓝图 11-aftersales-and-returns.md：「按质检结论把退货转入对应状态：可再销售转可售库存、
 * 残次转残次库存（可用于清仓）、报废则核销并计入损耗。」输出：「库存状态变更分录；可售量更新；
 * 报废损耗记录。」
 * 加粗句：「<b>报废是不可逆的</b>，需审批与原因，不能由客服随手处理。」
 *
 * <p>这是**真实工作区**：数据全部来自 {@code GET /trade/return-dispositions} 等真实端点，
 * 加载 / 空 / 错误三态齐全，不是只有字段名的占位页。
 *
 * <p>⚠️ 页面**刻意没有**「一键报废」按钮：报废走「起草 → 提交待审 → 另一个人审批 → 执行」
 * 四步链，提交人与审批人不能是同一个人（服务端用 DB 触发器钉死）。
 * 蓝图加粗句正是这条规则的落点 —— 把不可逆动作做成一个随手点的按钮，等于没有这条规则。
 */
export default function AftersalesReturnDispositionsPage() {
  return <ReturnInventoryDispositionWorkspace />;
}
