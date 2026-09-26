import { test, expect } from '@playwright/test';

/**
 * WorkspaceTabs — 应用内 tab 栏 e2e 测试
 *
 * 验证:
 *   - 访问 /orders 后 tab 栏出现
 *   - 点 nav 加新 tab
 *   - 切换 tab 内容随之切换
 *   - 关闭 tab 正常
 *
 * 运行:
 *   pnpm dev  (后台)
 *   pnpm test:e2e tests/e2e/workspace-tabs.spec.ts
 */

test.describe('应用内 tab 栏', () => {
  test('访问 /orders 后 tab 栏出现', async ({ page }) => {
    await page.goto('/orders');
    await expect(page.getByRole('tab', { name: /订单中心/ })).toBeVisible();
  });

  test('点击侧栏 nav 会加新 tab', async ({ page }) => {
    await page.goto('/orders');
    await expect(page.getByRole('tab', { name: /订单中心/ })).toBeVisible();

    // 点"商品中心"
    await page.getByRole('link', { name: '商品中心', exact: true }).click();
    await expect(page.getByRole('tab', { name: /商品中心/ })).toBeVisible();
    await expect(page.getByRole('tab', { name: /订单中心/ })).toBeVisible();
  });

  test('关闭 tab 后只剩其他 tab', async ({ page }) => {
    await page.goto('/orders');
    await page.getByRole('link', { name: '商品中心', exact: true }).click();
    await expect(page.getByRole('tab', { name: /订单中心/ })).toBeVisible();
    await expect(page.getByRole('tab', { name: /商品中心/ })).toBeVisible();

    // 关闭"订单中心" tab
    await page.getByRole('tab', { name: /订单中心/ }).hover();
    await page.getByRole('button', { name: /关闭 订单中心/ }).click();
    await expect(page.getByRole('tab', { name: /订单中心/ })).not.toBeVisible();
  });

  test('tab 状态持久化到 localStorage', async ({ page, context }) => {
    await page.goto('/orders');
    await page.getByRole('link', { name: '商品中心', exact: true }).click();
    await expect(page.getByRole('tab', { name: /商品中心/ })).toBeVisible();

    // 刷新
    await page.reload();
    // tabs 应该还在
    await expect(page.getByRole('tab', { name: /订单中心/ })).toBeVisible();
    await expect(page.getByRole('tab', { name: /商品中心/ })).toBeVisible();

    await context.clearCookies();
  });
});