import { it, expect, describe } from 'vitest';
import { existsSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
import { NAV_DESTINATIONS, SYSTEMS, TOP_LEVEL_ITEMS, ACCOUNT_DESTINATIONS } from './nav-config';
import { TASK_PAGES } from './task-pages';

/**
 * 菜单与路由的单一守卫。
 *
 * 背景：功能蓝图 docs/product/capability-blueprint/README.md §7 列出了
 * /channels、/ads、/logistics/customs、/finance/costing、/settings/approvals
 * 等入口，但这些页面尚未实现。菜单必须只暴露真实存在的路由——
 * 否则用户点进去是 404，等于「有名字没功能」。
 * 这条测试让"先有页面、后有菜单"成为硬约束，而不是靠自觉。
 */

const APP_DIR = join(process.cwd(), 'apps/web/src/app');

/** 扫描 Next.js app 目录，收集真实存在的页面路由。 */
function collectRoutes(dir: string, prefix = ''): Set<string> {
  const routes = new Set<string>();
  if (!existsSync(dir)) return routes;
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    if (entry.name.startsWith('.') || entry.name.startsWith('_')) continue;
    const full = join(dir, entry.name);
    if (entry.isDirectory()) {
      const segment = entry.name.startsWith('[') ? '' : entry.name;
      for (const route of collectRoutes(full, `${prefix}/${segment}`)) routes.add(route);
      if (existsSync(join(full, 'page.tsx'))) routes.add(`${prefix}/${segment}`.replace(/\/$/, '') || '/');
    } else if (entry.name === 'page.tsx') {
      routes.add(prefix === '' ? '/' : prefix);
    }
  }
  return routes;
}

const ROUTES = collectRoutes(APP_DIR);

/** 动态段（[...slug]）能兜住任意路径，这里单列出来豁免具体性检查。 */
const CATCH_ALL = '/[...slug]';

function isRoutable(href: string): boolean {
  if (existsSync(join(process.cwd(), `apps/web/src/app${CATCH_ALL}`))) return true;
  return ROUTES.has(href);
}

describe('菜单入口必须指向真实页面', () => {
  it('至少扫描到 app 路由（防止路径写错导致全部通过）', () => {
    expect(ROUTES.size).toBeGreaterThan(10);
    expect(ROUTES.has('/orders')).toBe(true);
  });

  it('业务系统入口全部可路由', () => {
    for (const system of SYSTEMS)
      for (const item of system.items) {
        expect(isRoutable(item.href), `${system.label}/${item.label} -> ${item.href} 没有对应页面`).toBe(
          true,
        );
      }
  });

  it('业务子页面全部可路由', () => {
    for (const item of NAV_DESTINATIONS) {
      expect(isRoutable(item.href), `子页面 ${item.href} 没有对应页面`).toBe(true);
    }
  });

  it('顶层固定项与账户入口全部可路由', () => {
    for (const item of TOP_LEVEL_ITEMS) expect(isRoutable(item.href)).toBe(true);
    for (const item of ACCOUNT_DESTINATIONS) expect(isRoutable(item.href)).toBe(true);
  });
});

describe('任务页与菜单入口一一对应', () => {
  it('任务页都有实现页面', () => {
    for (const page of TASK_PAGES) {
      expect(isRoutable(page.href), `任务页 ${page.href} 没有对应页面`).toBe(true);
    }
  });

  it('菜单入口不重复', () => {
    const hrefs = NAV_DESTINATIONS.map((item) => item.href);
    expect(new Set(hrefs).size).toBe(hrefs.length);
  });
});
