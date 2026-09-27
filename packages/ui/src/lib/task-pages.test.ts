import { it, expect } from 'vitest';
import { TASK_PAGES, findTaskPage } from './task-pages';
import {
  NAV_DESTINATIONS,
  SYSTEMS,
  resolveFeatureFromPath,
  resolveSystemFromPath,
} from './nav-config';
it('every task is uniquely addressable and has matching nav/header metadata', () => {
  expect(new Set(TASK_PAGES.map((page) => page.href)).size).toBe(TASK_PAGES.length);
  expect(NAV_DESTINATIONS).toHaveLength(TASK_PAGES.length);
  for (const page of TASK_PAGES) {
    expect(findTaskPage(page.href)).toBe(page);
    expect(resolveFeatureFromPath(page.href, resolveSystemFromPath(page.href))?.label).toBe(
      page.label,
    );
    expect(page.fields.length).toBeGreaterThan(0);
    expect(page.workflow).toHaveLength(3);
  }
  for (const system of SYSTEMS)
    for (const group of system.items) expect(group.children?.length).toBeGreaterThan(0);
  expect(findTaskPage('/orders/unknown')).toBeUndefined();
});
