import { ApprovalTodoWorkspace } from '@/components/dashboard/approval-todo-workspace';

/**
 * 1.1.1.3 审批待办（卷 01 · L3 1.1.1 待办与待决策 → {@code /dashboard/todo}）。
 *
 * <p>蓝图原文：「待我审批的单据统一入口，支持按单据类型、业务域、金额阈值、超时时长筛选；
 * 展示审批链路当前节点与已耗时长。」以及「**工作台不执行审批动作**，
 * 避免两处审批逻辑不一致」。
 *
 * <p>🔴 **本页面只做 1.1.1.3 这一格**，不把 1.1.1.1「我的待办」一起包进来 ——
 * 那一个模块尚未交付，由后续车道在同一路由下补。本页在结构上留好了扩展位
 * （顶部说明区与筛选区是共用的），但**不替兄弟模块实现**。
 *
 * <p>🔴 **路由位置**：本模块挂在 {@code /dashboard/todo/approvals}（1.1.1 这条 L3 锚点的子路由），
 * 而不是去改 {@code /dashboard/todo/page.tsx} —— 那个文件是兄弟模块 1.1.1.2
 * 「异常订单队列」的页面，本车道**不碰别人的文件**，并入时也就不会与之判冲突。
 *
 * <p>🔴 **工作台不写业务数据**：本页只读 + 跳转，审批动作一律回到 17 的审批详情页完成。
 */
export default function ApprovalTodoPage() {
  return <ApprovalTodoWorkspace />;
}
