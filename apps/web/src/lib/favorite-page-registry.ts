import { SYSTEMS, TOP_LEVEL_ITEMS, type SystemId } from '@starcon/ui';

/**
 * 1.2.2.1 常用页面收藏 —— **页面注册表**（前端这一侧的真相）。
 *
 * <p>蓝图原文：「**输入**：用户收藏/取消收藏操作；**页面注册表**（前端路由 + 标题 + 所属系统）。」
 * 本文件就是那份注册表：路由 + 标题 + 所属系统，三样齐了。
 *
 * <p><b>为什么不直接改共享的 {@code packages/ui/src/lib/nav-config.ts}</b>：
 *
 * <ul>
 *   <li>那个文件是<b>多车道共用</b>的导航单一真相源 —— 改它会让并行车道在同一个文件上判冲突；</li>
 *   <li>本模块需要的是「页面 ↔ 权限页码」的对应关系，而 {@code nav-config.ts} 是纯前端路由结构，
 *       没有权限维度（也不该有 —— 权限码是后端 {@code permission} 表的东西，
 *       前端只能照着已登记的码写映射）；</li>
 *   <li>所以这里**只读**共享注册表，在本车道另建一份映射 —— 共享文件一字未动。</li>
 * </ul>
 *
 * <p><b>页面注册表从共享 nav-config 派生</b>（{@code SYSTEMS} + {@code TOP_LEVEL_ITEMS}）：
 * 路由、标题、所属系统都直接取自共享真相源，本文件只补一份**权限页码**映射。
 *
 * <p><b>权限页码（{@code pageCode}）是什么</b>：目标页面在权限目录里的页码。
 * 服务端用它做**权限闸** —— {@code <pageCode>.read} 不在我的权限里时，这条收藏
 * **自动隐藏**（蓝图的硬约束：无权限的收藏自动隐藏）。
 *
 * <p><b>留空（{@code ''}）是什么意思</b>：这一页在权限目录里**没有** read 权限，
 * 因此**没有闸可过**，也就不会被权限闸挡住。
 * 本仓当前只有下列这些页登记了 read 权限（identity 服务 {@code permission} 表），
 * 其余页面（定价中心 / 售后中心 / 数据中心 / 财务中心 / 利润中心 / 物流中心 …）
 * 都没有页面级读权限 —— 它们的收藏对任何人都可见。
 */

/** 收藏项的所属系统：四个职能系统 + 顶层固定项。 */
export type FavoriteSystemId = SystemId | 'top';

/** 注册表里的一页。 */
export interface FavoritePage {
  /** 站内相对路径（点击直达的目标）。 */
  href: string;
  /** 页面标题。 */
  label: string;
  /** 所属系统。 */
  system: FavoriteSystemId;
  /** 目标页面的权限页码；空串 = 该页没有页面级读权限（因此不受权限闸约束）。 */
  pageCode: string;
}

/**
 * 路由 → 权限页码。
 *
 * <p><b>只登记真正存在 {@code <pageCode>.read} 权限的页面</b>：服务端在写入时会校验
 * 「这个页码真的登记过 read 权限」，没登记的会被 400 打回。
 * 留空的页面不是「忘了填」，而是**这一页确实没有页面级读权限**。
 */
const PAGE_CODES: Readonly<Record<string, string>> = {
  '/products': 'catalog.spu',
  '/products/research': 'catalog.research',
  '/products/compliance': 'catalog.compliance',
  '/products/assets': 'catalog.assets',
  '/purchase': 'trade.purchase',
  '/orders': 'trade.orders',
  '/warehouse': 'warehouse.facilities',
  '/warehouse/facilities': 'warehouse.facilities',
  '/inventory': 'warehouse.inventory',
  '/channels': 'channel.registry',
};

/** 系统的中文名（收藏清单里要显示「这个页面属于哪个系统」）。 */
export const FAVORITE_SYSTEM_LABELS: Readonly<Record<FavoriteSystemId, string>> = {
  dev: '开发',
  ops: '运营',
  logistics: '物流',
  storage: '仓储',
  top: '工作台',
};

/** 顶层固定项的归属：不属于任何职能系统，但仍是可收藏的页面。 */
const TOP_SYSTEM: FavoriteSystemId = 'top';

function pageCodeOf(href: string): string {
  return PAGE_CODES[href] ?? '';
}

/**
 * 全量页面注册表。
 *
 * <p>派生方式：四个职能系统的每个功能入口 + 它们的二级页面 + 三个顶层固定项。
 * 按 href 去重（一个页面只登记一次）。
 */
export const FAVORITE_PAGES: readonly FavoritePage[] = (() => {
  const pages: FavoritePage[] = [];
  const seen = new Set<string>();
  const push = (page: FavoritePage) => {
    if (seen.has(page.href)) return;
    seen.add(page.href);
    pages.push(page);
  };
  for (const system of SYSTEMS) {
    for (const item of system.items) {
      push({
        href: item.href,
        label: item.label,
        system: system.id,
        pageCode: pageCodeOf(item.href),
      });
      for (const child of item.children ?? []) {
        push({
          href: child.href,
          label: child.label,
          system: system.id,
          pageCode: pageCodeOf(child.href),
        });
      }
    }
  }
  for (const item of TOP_LEVEL_ITEMS) {
    push({
      href: item.href,
      label: item.label,
      system: TOP_SYSTEM,
      pageCode: pageCodeOf(item.href),
    });
  }
  return pages;
})();

/** 按路径找一页。 */
export function findFavoritePage(href: string): FavoritePage | undefined {
  return FAVORITE_PAGES.find((page) => page.href === href);
}

/** 按路径取权限页码（没登记就是空串）。 */
export function pageCodeFor(href: string): string {
  return findFavoritePage(href)?.pageCode ?? '';
}

/** 按系统分组（收集页用）。 */
export function groupFavoritePages(
  pages: readonly FavoritePage[],
): { system: FavoriteSystemId; label: string; pages: FavoritePage[] }[] {
  const order: FavoriteSystemId[] = ['dev', 'ops', 'logistics', 'storage', 'top'];
  return order
    .map((system) => ({
      system,
      label: FAVORITE_SYSTEM_LABELS[system],
      pages: pages.filter((page) => page.system === system),
    }))
    .filter((group) => group.pages.length > 0);
}
