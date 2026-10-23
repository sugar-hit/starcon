import { BadDebtWorkspace } from '@/components/finance/bad-debt-workspace';

/**
 * 12.1.1.3 坏账与核销。
 *
 * <p>蓝图 12-finance.md：「处理坏账：争议损失确认、买家拒付、平台冻结无法收回的核销，保留核销依据与审批。」输入：「核销申请；依据材料；审批。」输出：「坏账核销记录 + 会计处理 + 审计留痕。」
 * 加粗句：「<b>核销是财务终审动作</b>，需要完整依据链，<b>不允许批量一键核销无审批</b>。」
 *
 * <p>这是一个**真实工作区**：核销申请列表 + 依据材料增删 + 提交审批 + 审批（审批人 ≠ 提交人、
 * 意见必填）+ 执行核销（作废原单 + 新建承接单）+ 审计留痕，
 * 加载 / 空 / 错误三态齐全，**没有占位数据**。
 *
 * <p>路由取 {@code /finance/bad-debt} —— 与 12.1.1.1 的 {@code /finance}（应收单管理）、
 * 12.1.1.2 的 {@code /finance/aging}（应收账龄）**并列**而非嵌套：
 * 那是别的模块的页面（别的命名空间、别的端点、别的契约）。
 *
 * <p>⚠️ 这个 href **不在** {@code packages/ui/src/lib/task-pages.ts} 的注册表里 ——
 * 那是另一条车道拥有的共享文件，本车道不碰它。
 */
export default function BadDebtPage() {
  return <BadDebtWorkspace />;
}
