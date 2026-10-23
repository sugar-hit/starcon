import { WorkbenchLayoutWorkspace } from '@/components/dashboard/workbench-layout-workspace';

/**
 * 1.4.1.1 工作台布局（卷 01 · L3 1.4 个人设置 → 1.4.1 界面偏好）。
 *
 * <p>蓝图原文：
 * 「**职责**：用户拖拽调整指标卡顺序、隐藏不需要的卡片、选择默认落地 Tab。
 * **输入**：拖拽/显隐操作；系统可选卡片清单。
 * **输出**：个性化后的工作台布局；可重置为默认布局。
 * **关系**：上游 = 前端卡片注册表；下游 = 1.1 全部卡片渲染。
 * **布局是纯个人偏好，不进业务库。**」
 *
 * <p>🔴 **本模块不新建任何表、也不新增任何端点**：
 * 布局是**纯个人偏好**，落在 identity 的 {@code user_preference} 表里
 * （1.4.1.2 建的那张表），用本模块自己的 page_key {@code dashboard.layout}。
 * 载荷 {@code {order, hidden, defaultTab}} 走既有
 * {@code GET/PUT/DELETE /identity/user-preferences/{pageKey}} 端点 ——
 * 那个端点的载荷校验是**通用形状**校验（不是按 page key 分派），
 * 所以本模块的载荷形状直接被它覆盖，**不需要**改动共享的校验规则。
 *
 * <p>🔴 **路由用 {@code /dashboard/workbench-layout} 而不是 {@code /dashboard/layout}**：
 * {@code layout} 是 Next.js App Router 的保留文件名，用作目录名容易踩坑。
 *
 * <p>本页面**不写业务数据**：它只改「我这一份个人偏好」。
 */
export default function WorkbenchLayoutPage() {
  return <WorkbenchLayoutWorkspace />;
}
