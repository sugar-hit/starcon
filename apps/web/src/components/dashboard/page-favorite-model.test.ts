import { describe, expect, it } from 'vitest';
import {
  FAVORITE_PAGES,
  FAVORITE_SYSTEM_LABELS,
  findFavoritePage,
  groupFavoritePages,
  pageCodeFor,
} from '../../lib/favorite-page-registry';
import {
  EMPTY_FAVORITES,
  applyMove,
  canReadFavoritePage,
  describeFavoriteSystem,
  dropTargetIndex,
  favoriteIndexOf,
  filterFavoritePages,
  isFavorite,
  moveTargetIndex,
  parseFavoriteSet,
  toFavorite,
  type PageFavorite,
} from './page-favorite-model';

/**
 * 1.2.2.1 常用页面收藏 —— 前端模型的单元测试。
 *
 * <p>模型层放的是**纯函数**（形状解析 / 排序计算 / 筛选 / 权限判定），
 * 所以这些规则能被逐条钉住，而不是只能靠手点界面验证。
 */

function favorite(href: string, order: number, pageCode = ''): PageFavorite {
  return { href, label: `标题 ${href}`, system: 'ops', pageCode, order };
}

// ================================================================ 形状解析

describe('收藏清单解析', () => {
  it('解析正常的清单并按可见顺序重新编号', () => {
    const state = parseFavoriteSet({
      items: [
        { href: '/orders', label: '订单中心', system: 'ops', pageCode: 'trade.orders', order: 0 },
        { href: '/inventory', label: '库存中心', system: 'storage', pageCode: '', order: 1 },
      ],
      hiddenByPermission: 2,
      total: 4,
    });
    expect(state.items.map((item) => item.href)).toEqual(['/orders', '/inventory']);
    expect(state.items.map((item) => item.order)).toEqual([0, 1]);
    expect(state.hiddenByPermission).toBe(2);
    expect(state.total).toBe(4);
  });

  it('缺字段或类型不对的条目被跳过而不是让整个清单崩掉', () => {
    const state = parseFavoriteSet({
      items: [
        { href: '/orders', label: '订单中心', system: 'ops', pageCode: '', order: 0 },
        { label: '没有路径', system: 'ops' },
        { href: '/inventory' },
        null,
        '不是对象',
        { href: '/warehouse', label: '仓储中心', system: 'storage', pageCode: null, order: 9 },
      ],
      hiddenByPermission: 0,
      total: 1,
    });
    expect(state.items.map((item) => item.href)).toEqual(['/orders', '/warehouse']);
    // order 以可见清单里的下标为准（服务端已过滤掉无权访问的那些）
    expect(state.items[1]!.order).toBe(1);
    expect(state.items[1]!.pageCode).toBe('');
  });

  it('响应不是对象时退回空清单', () => {
    expect(parseFavoriteSet(null)).toEqual(EMPTY_FAVORITES);
    expect(parseFavoriteSet('nope')).toEqual(EMPTY_FAVORITES);
    expect(parseFavoriteSet({ items: 'nope' })).toEqual(EMPTY_FAVORITES);
  });

  it('总数不得小于可见条数', () => {
    const state = parseFavoriteSet({
      items: [{ href: '/a', label: 'A', system: 'ops', pageCode: '', order: 0 }],
      hiddenByPermission: -1,
      total: 0,
    });
    expect(state.total).toBe(1);
    expect(state.hiddenByPermission).toBe(0);
  });

  it('单条形状缺路径或缺标题时返回 null', () => {
    expect(toFavorite({ label: 'A', system: 'ops' })).toBeNull();
    expect(toFavorite({ href: '/a', system: 'ops' })).toBeNull();
    expect(toFavorite(null)).toBeNull();
    expect(toFavorite({ href: '/a', label: 'A', system: 'ops', pageCode: '', order: 0 })).not.toBeNull();
  });
});

// ================================================================ 排序

describe('排序', () => {
  const items = [favorite('/a', 0), favorite('/b', 1), favorite('/c', 2)];

  it('上移与下移的目标位', () => {
    expect(moveTargetIndex(items, '/b', 'up')).toBe(0);
    expect(moveTargetIndex(items, '/b', 'down')).toBe(2);
  });

  it('在边界上时目标位就是当前位置（按钮该禁用）', () => {
    expect(moveTargetIndex(items, '/a', 'up')).toBe(0);
    expect(moveTargetIndex(items, '/c', 'down')).toBe(2);
  });

  it('找不到这一条时返回 -1', () => {
    expect(moveTargetIndex(items, '/zzz', 'up')).toBe(-1);
    expect(favoriteIndexOf(items, '/zzz')).toBe(-1);
    expect(isFavorite(items, '/zzz')).toBe(false);
    expect(isFavorite(items, '/a')).toBe(true);
  });

  it('本地重排与「移到第 N 位」一致', () => {
    expect(applyMove(items, '/c', 0).map((item) => item.href)).toEqual(['/c', '/a', '/b']);
    expect(applyMove(items, '/a', 2).map((item) => item.href)).toEqual(['/b', '/c', '/a']);
    expect(applyMove(items, '/b', 1).map((item) => item.href)).toEqual(['/a', '/b', '/c']);
  });

  it('重排后顺序号连续', () => {
    const moved = applyMove(items, '/c', 0);
    expect(moved.map((item) => item.order)).toEqual([0, 1, 2]);
  });

  it('目标位越界时夹到两端', () => {
    expect(applyMove(items, '/a', 99).map((item) => item.href)).toEqual(['/b', '/c', '/a']);
    expect(applyMove(items, '/c', -5).map((item) => item.href)).toEqual(['/c', '/a', '/b']);
  });

  it('重排不改动条目本身的内容', () => {
    const withCode = [favorite('/a', 0, 'trade.orders'), favorite('/b', 1)];
    const moved = applyMove(withCode, '/a', 1);
    expect(moved[1]!.pageCode).toBe('trade.orders');
    expect(moved[1]!.label).toBe('标题 /a');
  });

  it('拖拽落点换算：被拖的那一条落到目标下标上', () => {
    // 从第 0 项拖到第 2 项上 ⇒ 目标是第 2 位（结果 [b, c, a]）
    expect(dropTargetIndex(items, 0, 2)).toBe(2);
    // 从第 2 项拖到第 0 项上 ⇒ 目标是第 0 位（结果 [c, a, b]）
    expect(dropTargetIndex(items, 2, 0)).toBe(0);
    // 落点越界夹紧
    expect(dropTargetIndex(items, 0, 99)).toBe(2);
    expect(dropTargetIndex(items, 2, -5)).toBe(0);
    // 源下标非法
    expect(dropTargetIndex(items, -1, 1)).toBe(-1);
    expect(dropTargetIndex(items, 3, 1)).toBe(-1);
  });

  it('拖拽与按钮走的是同一套语义：都落到目标下标上', () => {
    expect(applyMove(items, '/a', dropTargetIndex(items, 0, 2)).map((item) => item.href)).toEqual([
      '/b',
      '/c',
      '/a',
    ]);
    expect(applyMove(items, '/c', dropTargetIndex(items, 2, 0)).map((item) => item.href)).toEqual([
      '/c',
      '/a',
      '/b',
    ]);
  });
});

// ================================================================ 权限闸

describe('权限闸（前端镜像）', () => {
  it('没有页面码 = 这一页没有页面级读权限 = 没有闸', () => {
    expect(canReadFavoritePage('', [])).toBe(true);
  });

  it('有页面码时必须持有对应的 read 权限', () => {
    expect(canReadFavoritePage('trade.orders', ['trade.orders.read'])).toBe(true);
    expect(canReadFavoritePage('trade.orders', ['catalog.spu.read'])).toBe(false);
    expect(canReadFavoritePage('trade.orders', [])).toBe(false);
  });
});

// ================================================================ 注册表

describe('页面注册表', () => {
  it('注册表非空且路径唯一', () => {
    expect(FAVORITE_PAGES.length).toBeGreaterThan(0);
    const hrefs = FAVORITE_PAGES.map((page) => page.href);
    expect(new Set(hrefs).size).toBe(hrefs.length);
  });

  it('每一页都有标题与所属系统', () => {
    for (const page of FAVORITE_PAGES) {
      expect(page.label.length).toBeGreaterThan(0);
      expect(Object.keys(FAVORITE_SYSTEM_LABELS)).toContain(page.system);
    }
  });

  it('路径都是站内相对路径', () => {
    for (const page of FAVORITE_PAGES) {
      expect(page.href.startsWith('/')).toBe(true);
      expect(page.href.startsWith('//')).toBe(false);
      expect(page.href).not.toContain('..');
    }
  });

  it('登记了页面码的页，页面码形状受控', () => {
    for (const page of FAVORITE_PAGES) {
      if (!page.pageCode) continue;
      expect(page.pageCode).toMatch(/^[a-z0-9][a-z0-9._-]{0,63}$/);
      expect(page.pageCode).toContain('.');
    }
  });

  it('能按路径查到页与页面码', () => {
    expect(findFavoritePage('/orders')?.label.length).toBeGreaterThan(0);
    expect(pageCodeFor('/orders')).toBe('trade.orders');
    expect(pageCodeFor('/not-registered')).toBe('');
    expect(findFavoritePage('/not-registered')).toBeUndefined();
  });

  it('分组后每个页面只出现在一个组里', () => {
    const groups = groupFavoritePages(FAVORITE_PAGES);
    const total = groups.reduce((sum, group) => sum + group.pages.length, 0);
    expect(total).toBe(FAVORITE_PAGES.length);
    for (const group of groups) {
      expect(group.pages.length).toBeGreaterThan(0);
      for (const page of group.pages) expect(page.system).toBe(group.system);
    }
  });

  it('关键字筛选真的过滤掉了页面', () => {
    const all = filterFavoritePages(FAVORITE_PAGES, '');
    expect(all.length).toBe(FAVORITE_PAGES.length);

    const byLabel = filterFavoritePages(FAVORITE_PAGES, '订单');
    expect(byLabel.length).toBeGreaterThan(0);
    expect(byLabel.length).toBeLessThan(FAVORITE_PAGES.length);
    for (const page of byLabel) {
      expect(`${page.label}${page.href}`.toLowerCase()).toContain('订单');
    }

    const byPath = filterFavoritePages(FAVORITE_PAGES, '/inventory');
    expect(byPath.map((page) => page.href)).toContain('/inventory');

    expect(filterFavoritePages(FAVORITE_PAGES, '   ').length).toBe(FAVORITE_PAGES.length);
    expect(filterFavoritePages(FAVORITE_PAGES, 'zzz-不存在')).toEqual([]);
  });
});

describe('系统名', () => {
  it('已知的系统给出中文名', () => {
    expect(describeFavoriteSystem('ops')).toBe('运营');
    expect(describeFavoriteSystem('top')).toBe('工作台');
  });

  it('未知的系统原样返回（不吞掉信息）', () => {
    expect(describeFavoriteSystem('weird')).toBe('weird');
  });
});
