import { it, expect } from 'vitest';
import { TASK_PAGES, findTaskPage, isAccountDomain } from './task-pages';
import {
  NAV_DESTINATIONS,
  ACCOUNT_DESTINATIONS,
  SYSTEMS,
  TOP_LEVEL_ITEMS,
  resolveFeatureFromPath,
  resolveSystemFromPath,
} from './nav-config';

/** 账户/组织页面不进入业务菜单；它们由账户卡片与账户 Tab 承载。 */
const BUSINESS_TASKS = TASK_PAGES.filter((page) => !isAccountDomain(page.domain));
const ACCOUNT_TASKS = TASK_PAGES.filter((page) => isAccountDomain(page.domain));

it('every task is uniquely addressable and has matching nav/header metadata', () => {
  expect(new Set(TASK_PAGES.map((page) => page.href)).size).toBe(TASK_PAGES.length);
  for (const page of TASK_PAGES) {
    expect(findTaskPage(page.href)).toBe(page);
    expect(page.fields.length).toBeGreaterThan(0);
    expect(page.workflow).toHaveLength(3);
  }
  expect(findTaskPage('/orders/unknown')).toBeUndefined();
});

it('业务菜单只承载业务页面，账户页面走账户通道', () => {
  // ce5c78e 起组织与权限已移出业务系统，改由账户卡片进入。
  // 因此业务菜单条目数等于「业务任务页数」，不再等于全部任务页数。
  expect(NAV_DESTINATIONS).toHaveLength(BUSINESS_TASKS.length);
  expect(ACCOUNT_TASKS.length).toBeGreaterThan(0);
  for (const page of ACCOUNT_TASKS) {
    expect(NAV_DESTINATIONS.some((item) => item.href === page.href)).toBe(false);
  }
  for (const page of BUSINESS_TASKS) {
    expect(NAV_DESTINATIONS.some((item) => item.href === page.href)).toBe(true);
  }
});

it('业务页面的标题与其所属系统导航一致', () => {
  for (const page of BUSINESS_TASKS) {
    expect(resolveFeatureFromPath(page.href, resolveSystemFromPath(page.href))?.label).toBe(
      page.label,
    );
  }
});

it('账户页面不被任何业务系统解析认领', () => {
  for (const page of ACCOUNT_TASKS) {
    const system = resolveSystemFromPath(page.href);
    // 不应落到某个业务系统的功能上；组织页面统一从账户入口进入
    expect(
      system.items.some((item) => item.href === `/${page.domain}`),
    ).toBe(false);
  }
  // 反向不变量：账户任务页必须都有菜单入口，杜绝「有页面没入口」的割裂
  const accountHrefs = new Set(ACCOUNT_DESTINATIONS.map((d) => d.href));
  for (const page of ACCOUNT_TASKS) {
    expect(accountHrefs.has(page.href), `账户页 ${page.href} 没有菜单入口`).toBe(true);
  }
  for (const destination of ACCOUNT_DESTINATIONS) {
    expect(findTaskPage(destination.href)).toBeDefined();
  }
});

it('每个导航分组都有子页面，账户页不混进业务分组', () => {
  for (const system of SYSTEMS)
    for (const group of system.items) {
      expect(group.children?.length).toBeGreaterThan(0);
      for (const child of group.children ?? []) {
        const page = findTaskPage(child.href);
        expect(page, `子页面 ${child.href} 不是任务页`).toBeDefined();
        expect(isAccountDomain(page!.domain), `${child.href} 是账户页，不应出现在业务分组`).toBe(false);
      }
    }
  for (const item of TOP_LEVEL_ITEMS) expect(item.href.startsWith('/')).toBe(true);
});

it('IAM 管理页面独立于商品中心，商品页面不承载身份管理', () => {
  for (const kind of ['roles', 'members', 'departments', 'children']) {
    expect(findTaskPage(`/products/${kind}`)).toBeUndefined();
    expect(findTaskPage(`/org/${kind}`)?.domain).toBe('org');
  }
});
