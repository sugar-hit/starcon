import { MyTodoWorkspace } from '@/components/dashboard/my-todo-workspace';

/**
 * 1.1.1.1 我的待办（卷 01 · L3 1.1.1 待办与待决策 → {@code /dashboard/todo}）。
 *
 * <p>蓝图原文：「把散落在 12 个系统的『需要人处理』的动作收敛成一条**按紧急度排序**的队列，
 * 每项都有明确的下一步入口。覆盖：超期未发货、待审订单、库存缺口、待处理同步失败、
 * 待核对账差异、待回复站内信、待审批单据、库存低于安全线。」
 *
 * <p>🔴 **本页面只做 1.1.1.1 这一格** —— 不把兄弟模块 1.1.1.2「异常订单队列」
 * 与 1.1.1.3「审批待办」一起包进来：那两格各有自己的页面
 * （{@code /dashboard/todo} 与 {@code /dashboard/todo/approvals}），
 * 本车道**不碰别人的文件**，并入时也就不会与之判冲突。
 *
 * <p>🔴 **路由位置**：本模块挂在 {@code /dashboard/todo/my-todos}
 * （1.1.1 这条 L3 锚点的子路由，与 1.1.1.3 的 {@code /dashboard/todo/approvals} 同层），
 * 而不是去改 {@code /dashboard/todo/page.tsx} —— 那个文件是兄弟模块 1.1.1.2
 * 「异常订单队列」的页面。
 *
 * <p>🔴 **工作台不写业务数据**：本页只读 + 跳转，
 * 认领 / 完成 / 转交这类写动作一律回到归属系统完成。
 */
export default function MyTodoPage() {
  return <MyTodoWorkspace />;
}
