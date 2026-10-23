import { PageFavoriteWorkspace } from '@/components/dashboard/page-favorite-workspace';

/**
 * 1.2.2.1 常用页面收藏 —— 独立页（`/dashboard/favorites`）。
 *
 * <p>蓝图把 1.2.2「快捷入口」的路由锚定在 `/dashboard/shortcuts`，而那一格（1.2.2.2
 * 快捷入口配置）已经由另一条车道交付并占用了那个路由。本格做的是**另一件事**
 * （用户自己收藏高频页面），所以另开一个路由，避免改动别人的页面。
 *
 * <p><b>它不是占位页</b>：真实的收藏清单、真实的添加/取消、真实的拖拽排序、
 * 真实的权限隐藏提示，加载 / 错误 / 空三态齐全。数据全部来自 identity 服务的真实端点。
 */
export default function PageFavoritesPage() {
  return <PageFavoriteWorkspace />;
}
