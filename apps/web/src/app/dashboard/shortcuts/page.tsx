import { ShortcutTemplateWorkspace } from '@/components/dashboard/shortcut-template-workspace';

/**
 * 1.2.2.2 快捷入口配置 —— 独立页（`/dashboard/shortcuts`）。
 *
 * <p>蓝图把 1.2.2「快捷入口」的路由定在这里。本页承担的是 1.2.2.2 这一格：
 * **岗位快捷入口模板 + 个人覆盖项 + 最终生效的快捷入口集合**。
 *
 * <p><b>它不是占位页</b>：真实的模板列表、真实的生效集合、真实的个人覆盖操作，
 * 加载 / 错误 / 空三态齐全。数据全部来自 identity 服务的真实端点，
 * 合并规则在服务端算完（那里才有「我命中哪些模板」「我持有哪些权限」的真值）。
 *
 * <p><b>1.2.2.1 常用页面收藏</b>（用户自己收藏页面、可拖拽排序）**未交付**，
 * 由另一条车道负责 —— 本页只提供岗位模板与个人覆盖，不造收藏功能。
 */
export default function ShortcutTemplatePage() {
  return <ShortcutTemplateWorkspace />;
}
