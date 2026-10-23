import type {
  PageFavoriteItem,
  PageFavoriteSet,
} from '../../../../../packages/api-contracts/src/index';
import {
  FAVORITE_SYSTEM_LABELS,
  type FavoritePage,
  type FavoriteSystemId,
} from '../../lib/favorite-page-registry';

/**
 * 1.2.2.1 常用页面收藏 —— 前端模型（纯函数）。
 *
 * <p>蓝图原文：「**输出**：收藏列表（有序、支持拖拽排序）；点击直达。」
 *
 * <p>本文件只放**纯函数**：形状解析、排序计算、筛选、权限判定。
 * 界面组件只负责渲染与发请求 —— 业务规则一旦写进组件里，
 * 就没法被单元测试逐条钉住（回归时只能靠手点）。
 */

/** 一条收藏（前端形状）。 */
export interface PageFavorite {
  href: string;
  label: string;
  system: string;
  /** 目标页面的权限页码；空串 = 该页没有页面级读权限（不受权限闸约束）。 */
  pageCode: string;
  /** 在**可见清单**里的位置（0 起）。 */
  order: number;
}

/** 我的收藏清单。 */
export interface PageFavoriteState {
  /** 可见的收藏（已按顺序排好）。 */
  items: PageFavorite[];
  /** 因当前没有该页面的读权限而被隐藏的条数。 */
  hiddenByPermission: number;
  /** 存储的收藏总条数（含被隐藏的）。 */
  total: number;
}

/** 空清单。 */
export const EMPTY_FAVORITES: PageFavoriteState = {
  items: [],
  hiddenByPermission: 0,
  total: 0,
};

/**
 * 契约形状 → 前端形状。
 *
 * <p>容错：字段缺失或类型不对时**跳过这一条**，而不是让整个清单崩掉
 * （一条坏数据不该让用户整个工作台打不开）。
 */
export function toFavorite(raw: unknown): PageFavorite | null {
  if (typeof raw !== 'object' || raw === null) return null;
  const item = raw as Partial<PageFavoriteItem>;
  if (typeof item.href !== 'string' || item.href.length === 0) return null;
  if (typeof item.label !== 'string' || item.label.length === 0) return null;
  return {
    href: item.href,
    label: item.label,
    system: typeof item.system === 'string' ? item.system : 'top',
    pageCode: typeof item.pageCode === 'string' ? item.pageCode : '',
    order: typeof item.order === 'number' ? item.order : 0,
  };
}

/**
 * 契约形状 → 前端清单。
 *
 * <p>服务端已经把无权限的收藏**过滤掉**了，这里只负责解析与重新编号
 * （`order` 以**可见清单**里的下标为准 —— 界面上渲染的就是这个顺序）。
 */
export function parseFavoriteSet(raw: unknown): PageFavoriteState {
  if (typeof raw !== 'object' || raw === null) return EMPTY_FAVORITES;
  const set = raw as Partial<PageFavoriteSet>;
  const rawItems = Array.isArray(set.items) ? set.items : [];
  const items: PageFavorite[] = [];
  for (const rawItem of rawItems) {
    const favorite = toFavorite(rawItem);
    if (favorite) items.push({ ...favorite, order: items.length });
  }
  const hidden =
    typeof set.hiddenByPermission === 'number' && set.hiddenByPermission > 0
      ? set.hiddenByPermission
      : 0;
  const total = typeof set.total === 'number' && set.total >= items.length ? set.total : items.length;
  return { items, hiddenByPermission: hidden, total };
}

/** 某条收藏在清单里的下标；没有就返回 −1。 */
export function favoriteIndexOf(items: readonly PageFavorite[], href: string): number {
  return items.findIndex((item) => item.href === href);
}

/** 这条收藏是不是已经在清单里。 */
export function isFavorite(items: readonly PageFavorite[], href: string): boolean {
  return favoriteIndexOf(items, href) >= 0;
}

/** 排序方向。 */
export type MoveDirection = 'up' | 'down';

/**
 * 「上移 / 下移」按钮的目标位。
 *
 * <p>与服务端 {@code PUT /identity/page-favorites/order} 的语义**完全一致**：
 * 目标位是**可见清单**里的下标，越界时服务端会夹到两端 ——
 * 这里先夹一次，好让按钮在边界上直接禁用而不是发一个必然被夹的请求。
 *
 * @returns 目标下标；已经在边界上时返回当前下标（表示「不用动」）
 */
export function moveTargetIndex(
  items: readonly PageFavorite[],
  href: string,
  direction: MoveDirection,
): number {
  const index = favoriteIndexOf(items, href);
  if (index < 0) return -1;
  const target = direction === 'up' ? index - 1 : index + 1;
  if (target < 0 || target >= items.length) return index;
  return target;
}

/**
 * 本地乐观重排（把一条收藏移到可见清单的第 {@code toIndex} 位）。
 *
 * <p>与服务端 {@code PageFavoriteRules.move} 对**可见清单**的结果一致
 * （服务端另外还要处理被隐藏项的绝对位置，前端看不到那些项，所以结果相同）。
 */
export function applyMove(
  items: readonly PageFavorite[],
  href: string,
  toIndex: number,
): PageFavorite[] {
  const index = favoriteIndexOf(items, href);
  if (index < 0) return items.map((item, i) => ({ ...item, order: i }));
  const target = Math.max(0, Math.min(toIndex, items.length - 1));
  const next = items.map((item, i) => ({ ...item, order: i }));
  const [moved] = next.splice(index, 1);
  if (!moved) return next;
  next.splice(target, 0, moved);
  return next.map((item, i) => ({ ...item, order: i }));
}

/**
 * 拖拽落点 → 目标下标。
 *
 * <p>语义：**被拖的那一条落到目标下标上**（不是插到它前面）。
 * 于是「把第 0 项拖到第 2 项上」的结果是它排到第 2 位、原第 2 项被挤到第 1 位 ——
 * 这与「上移/下移按钮」的语义一致（都是「移到第 N 位」），两条路径不会给出不同的顺序。
 *
 * <p>落点越界时夹到两端；源下标非法时返回 −1（表示「别动」）。
 */
export function dropTargetIndex(
  items: readonly PageFavorite[],
  fromIndex: number,
  dropIndex: number,
): number {
  if (fromIndex < 0 || fromIndex >= items.length) return -1;
  return Math.max(0, Math.min(dropIndex, items.length - 1));
}

/**
 * 页面在当前会话下**能不能读**。
 *
 * <p>这是服务端权限闸在前端的镜像：{@code pageCode} 为空 ⇒ 这一页没有页面级读权限
 * ⇒ 没有闸；否则要持有 {@code <pageCode>.read}。
 *
 * <p>前端的这个判定只用来**提前告诉用户「这一页你看不到」**（别让用户收藏完才发现是空的）；
 * 真正的闸在服务端 —— 前端判定可以被绕过，所以服务端那道必须自己站住。
 */
export function canReadFavoritePage(pageCode: string, permissions: readonly string[]): boolean {
  if (!pageCode) return true;
  return permissions.includes(`${pageCode}.read`);
}

/** 关键字筛选注册表（真筛选：把页面真的过滤掉，不是假搜索）。 */
export function filterFavoritePages(
  pages: readonly FavoritePage[],
  keyword: string,
): FavoritePage[] {
  const needle = keyword.trim().toLowerCase();
  if (!needle) return [...pages];
  return pages.filter(
    (page) =>
      page.label.toLowerCase().includes(needle) || page.href.toLowerCase().includes(needle),
  );
}

/** 所属系统的中文名。 */
export function describeFavoriteSystem(system: string): string {
  return FAVORITE_SYSTEM_LABELS[system as FavoriteSystemId] ?? system;
}

/** 收藏清单里用到的系统分组顺序（与注册表一致）。 */
export const FAVORITE_SYSTEM_ORDER: readonly FavoriteSystemId[] = [
  'dev',
  'ops',
  'logistics',
  'storage',
  'top',
];
