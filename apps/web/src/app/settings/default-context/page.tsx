import { DefaultContextWorkspace } from './default-context-workspace';

/**
 * 1.4.2.2 默认店铺与仓库（卷 01 · L3 1.4.2 账号偏好）。
 *
 * <p>蓝图原文：
 * 「<b>职责</b>：设定默认进入的店铺与默认操作的法人/仓库，减少每次进入的重复选择。
 * <b>输入</b>：用户选择；用户有权限的店铺/仓库集合。
 * <b>输出</b>：默认上下文，注入后续页面初始筛选。
 * <b>关系</b>：上游 = 02 店铺、08 仓库、17 权限；下游 = 09、07、13 各页面的默认筛选。
 * <b>默认值必须在权限范围内</b>，权限变更后自动失效并回落。」
 *
 * <p><b>本页面不写业务数据</b>：它只改「我这一份」个人偏好
 * （identity 的 {@code user_preference} 表，页面 key {@code default-context}）。
 * 店铺 / 法人 / 仓库的事实仍归 02 / 17.1 / 08 三个服务所有。
 *
 * <p>🔴 **路由用 {@code /settings/default-context} 而不是 {@code /settings/default}**：
 * {@code default} 是 Next.js App Router 的保留名，用作目录名容易踩坑。
 */
export default function DefaultContextPage() {
  return <DefaultContextWorkspace />;
}
