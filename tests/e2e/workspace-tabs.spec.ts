import { test, expect } from './support/authenticated';

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
    await expect(page.getByRole('tab', { name: /全部订单/ })).toBeVisible();
  });

  test('点击侧栏 nav 会加新 tab', async ({ page }) => {
    await page.goto('/orders');
    await expect(page.getByRole('tab', { name: /全部订单/ })).toBeVisible();

    // 点"商品中心"
    await page
      .getByRole('navigation', { name: '系统导航' })
      .getByRole('link', { name: '开发', exact: true })
      .click();
    await expect(page).toHaveURL('/products');
    await expect(page.getByRole('tab', { name: /商品档案/ })).toBeVisible();
    await expect(page.getByRole('tab', { name: /全部订单/ })).toBeVisible();
  });

  test('页面导航提交时新 tab 与新页面一起出现', async ({ page }) => {
    await page.goto('/orders');
    await page.route('**/products?*', async (route) => {
      await new Promise((resolve) => setTimeout(resolve, 800));
      await route.continue();
    });
    const navigation = page
      .getByRole('navigation', { name: '系统导航' })
      .getByRole('link', { name: '开发', exact: true })
      .click();
    await expect(page.getByRole('tab', { name: /商品档案/ })).toHaveCount(0);
    await expect(page.getByRole('tab', { name: /全部订单/ })).toHaveAttribute(
      'aria-selected',
      'true',
    );
    await navigation;
    await expect(page).toHaveURL('/products');
    await expect(page.getByRole('tab', { name: /商品档案/ })).toHaveAttribute(
      'aria-selected',
      'true',
    );
    await expect(page.getByRole('heading', { name: '商品档案' }).first()).toBeVisible();
  });

  test('关闭 tab 后只剩其他 tab', async ({ page }) => {
    await page.goto('/orders');
    await page
      .getByRole('navigation', { name: '系统导航' })
      .getByRole('link', { name: '开发', exact: true })
      .click();
    await expect(page).toHaveURL('/products');
    await expect(page.getByRole('tab', { name: /全部订单/ })).toBeVisible();
    await expect(page.getByRole('tab', { name: /商品档案/ })).toBeVisible();

    // 关闭"订单中心" tab
    await page.getByRole('tab', { name: /全部订单/ }).hover();
    await page.getByRole('button', { name: /关闭 全部订单/ }).click();
    await expect(page.getByRole('tab', { name: /全部订单/ })).not.toBeVisible();
  });

  test('tab 状态持久化到 localStorage', async ({ page, context }) => {
    await page.goto('/orders');
    await page
      .getByRole('navigation', { name: '系统导航' })
      .getByRole('link', { name: '开发', exact: true })
      .click();
    await expect(page).toHaveURL('/products');
    await expect(page.getByRole('tab', { name: /商品档案/ })).toBeVisible();

    // 刷新
    await page.reload();
    // tabs 应该还在
    await expect(page.getByRole('tab', { name: /全部订单/ })).toBeVisible();
    await expect(page.getByRole('tab', { name: /商品档案/ })).toBeVisible();

    await context.clearCookies();
  });
});
