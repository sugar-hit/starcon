import { it, expect, describe } from 'vitest';
import { existsSync } from 'node:fs';
import { join } from 'node:path';
import { NAV_DESTINATIONS, SYSTEMS, TOP_LEVEL_ITEMS, ACCOUNT_DESTINATIONS } from './nav-config';
import { TASK_PAGES, IMPLEMENTED_TASK_PAGES, isAccountDomain, findTaskPage } from './task-pages';

/**
 * 菜单与真实页面的守卫。
 *
 * 背景：功能蓝图 docs/product/capability-blueprint/README.md 规划了 18 系统、465 个模块，
 * 但实现是分批推进的。此前 TASK_PAGES 里有 37 个任务页并没有对应的 page.tsx，
 * 点击后落到 [...slug] 兜底页——菜单看起来很全，实际点进去是空。
 *
 * 本测试把「implemented 标记」与真实文件系统对照：
 *   - 标记为 true  → 必须真的存在 page.tsx
 *   - 标记为 false → 不允许出现在任何菜单里
 *
 * 注意：这里**不能**把 [...slug] 当作兜底放行。上一版的守卫就是因此失效的，
 * 37 个不存在的页面全部「通过」了。
 */

const APP_DIR = join(process.cwd(), 'apps/web/src/app');

function hasPage(href: string): boolean {
  return href === '/'
    ? existsSync(join(APP_DIR, 'page.tsx'))
    : existsSync(join(APP_DIR, href.replace(/^\//, ''), 'page.tsx'));
}

describe('implemented 标记必须与真实文件系统一致', () => {
  it('每个任务页的 implemented 标记都符合真实路由', () => {
    const wrong = TASK_PAGES.filter((page) => page.implemented !== hasPage(page.href)).map(
      (page) => `${page.href} 标记=${page.implemented} 实际=${hasPage(page.href)}`,
    );
    expect(wrong).toEqual([]);
  });

  it('有真实页面的任务页不能被标成未实现（防止漏做入口）', () => {
    const missing = TASK_PAGES.filter((page) => hasPage(page.href) && !page.implemented);
    expect(missing.map((p) => p.href)).toEqual([]);
  });
});

describe('菜单只暴露已实现页面', () => {
  it('业务系统入口全部有真实页面', () => {
    for (const system of SYSTEMS)
      for (const item of system.items) {
        expect(hasPage(item.href), `${system.label}/${item.label} -> ${item.href} 无页面`).toBe(true);
      }
  });

  it('业务子页面全部已实现且有真实页面', () => {
    for (const item of NAV_DESTINATIONS) {
      const page = findTaskPage(item.href);
      expect(page, `${item.href} 不是任务页`).toBeDefined();
      expect(page!.implemented, `${item.href} 未实现却出现在菜单`).toBe(true);
      expect(hasPage(item.href), `${item.href} 无真实页面`).toBe(true);
    }
  });

  it('顶层固定项与账户入口全部已实现且有真实页面', () => {
    for (const item of TOP_LEVEL_ITEMS) {
      expect(hasPage(item.href), `顶层项 ${item.href} 无页面`).toBe(true);
    }
    for (const item of ACCOUNT_DESTINATIONS) {
      expect(hasPage(item.href), `账户入口 ${item.href} 无页面`).toBe(true);
      const page = findTaskPage(item.href);
      expect(page?.implemented, `账户入口 ${item.href} 未实现`).toBe(true);
    }
  });

  it('未实现的任务页不得进入任何菜单', () => {
    const menuHrefs = new Set<string>([
      ...NAV_DESTINATIONS.map((i) => i.href),
      ...ACCOUNT_DESTINATIONS.map((i) => i.href),
      ...SYSTEMS.flatMap((s) => s.items.map((i) => i.href)),
      ...TOP_LEVEL_ITEMS.map((i) => i.href),
    ]);
    for (const page of TASK_PAGES.filter((p) => !p.implemented)) {
      expect(menuHrefs.has(page.href), `${page.href} 未实现却出现在菜单`).toBe(false);
    }
  });
});

describe('菜单结构自洽', () => {
  it('业务系统分组不出现空子页', () => {
    for (const system of SYSTEMS)
      for (const item of system.items) {
        expect(item.children?.length, `${system.label}/${item.label} 没有子页面`).toBeGreaterThan(0);
      }
  });

  it('已实现任务页与菜单条目一一对应', () => {
    expect(NAV_DESTINATIONS).toHaveLength(
      IMPLEMENTED_TASK_PAGES.filter((p) => !isAccountDomain(p.domain)).length,
    );
  });

  it('账户域已实现页面全部有入口，无孤儿', () => {
    const accountHrefs = new Set(ACCOUNT_DESTINATIONS.map((d) => d.href));
    for (const page of IMPLEMENTED_TASK_PAGES.filter((p) => isAccountDomain(p.domain))) {
      expect(accountHrefs.has(page.href), `账户页 ${page.href} 没有入口`).toBe(true);
    }
  });

  it('菜单入口不重复', () => {
    const hrefs = NAV_DESTINATIONS.map((i) => i.href);
    expect(new Set(hrefs).size).toBe(hrefs.length);
  });
});
