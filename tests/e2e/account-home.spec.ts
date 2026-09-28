import { test, expect } from './support/authenticated';

test('home is permanent, first and selected after batch close or logo', async ({ page }) => {
  await page.goto('/products');
  const home = page.getByRole('tab', { name: '工作台', exact: true });
  await expect(page.getByRole('tab').first()).toHaveText('工作台');
  await expect(page.getByRole('button', { name: '关闭 工作台', exact: true })).toHaveCount(0);
  await expect(page.locator('[data-tab-id="/"]')).toHaveAttribute('draggable', 'false');
  await home.click({ button: 'middle' });
  await expect(home).toBeVisible();
  await expect(page).toHaveURL('/products');
  await home.click({ button: 'right' });
  await expect(page.getByRole('menuitem', { name: '关闭当前', exact: true })).toBeDisabled();
  await expect(page.getByRole('menuitem', { name: '关闭左侧', exact: true })).toBeDisabled();
  await page.getByRole('menuitem', { name: '关闭右侧', exact: true }).click();
  await expect(page).toHaveURL('/');
  await expect(page.getByRole('tab')).toHaveCount(1);
  await expect(home).toHaveAttribute('aria-selected', 'true');
  await page.reload();
  await expect(page.getByRole('tab')).toHaveCount(1);
  await home.click({ button: 'right' });
  await expect(page.getByRole('menuitem', { name: '关闭全部', exact: true })).toBeDisabled();
  await page.keyboard.press('Escape');
  await expect(page.getByRole('button', { name: '工作台', exact: true })).toHaveCount(0);
});

test('真实账户入口不提供演示提权，资料跟随服务端会话', async ({ page }) => {
  await page.goto('/products');
  await page.getByRole('button', { name: '账户与租户' }).click();
  await expect(page.getByText('交互原型 · 未接入真实账户')).toHaveCount(0);
  await expect(page.getByRole('radio', { name: '超级管理员' })).toHaveCount(0);
  await page.getByRole('link', { name: '我的账户', exact: true }).click();
  await expect(page).toHaveURL('/account');
  await expect(page.getByText('用户 ID', { exact: true })).toBeVisible();
  await page.reload();
  await expect(page.getByText('成员身份 ID', { exact: true })).toBeVisible();
});

for (const width of [320, 390, 768, 1024, 1920]) {
  test(`account and permanent tabs fit ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 800 });
    await page.goto('/');
    const account = page.getByRole('button', { name: '账户与租户' });
    await expect(account).toBeInViewport();
    const header = page.locator('header');
    expect(await header.evaluate((el) => el.scrollWidth <= el.clientWidth)).toBe(true);
    await account.click();
    await expect(page.getByLabel('切换租户')).toBeInViewport();
    await page.keyboard.press('Escape');
    await expect(account).toBeFocused();
  });
}
