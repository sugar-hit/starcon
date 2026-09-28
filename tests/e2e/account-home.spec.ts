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

test('工作台关闭其他会清空全部可关闭 tab', async ({ page }) => {
  await page.goto('/orders');
  await page
    .getByRole('navigation', { name: '系统导航' })
    .getByRole('link', { name: '开发', exact: true })
    .click();
  await expect(page.getByRole('tab')).toHaveCount(3);
  await page.getByRole('tab', { name: '工作台', exact: true }).click({ button: 'right' });
  await page.getByRole('menuitem', { name: '关闭其他', exact: true }).click();
  await expect(page.getByRole('tab')).toHaveCount(1);
  await expect(page.getByRole('tab', { name: '工作台', exact: true })).toHaveAttribute(
    'aria-selected',
    'true',
  );
});

test('真实账户入口不提供演示提权，资料跟随服务端会话', async ({ page }) => {
  await page.goto('/products');
  await page.getByRole('button', { name: '账户与租户' }).click();
  await expect(page.getByText('交互原型 · 未接入真实账户')).toHaveCount(0);
  await expect(page.getByRole('radio', { name: '超级管理员' })).toHaveCount(0);
  await page.getByRole('link', { name: '个人资料与头像', exact: true }).click();
  await expect(page).toHaveURL('/account');
  await expect(page.locator('[data-tab-id="/account"]')).toHaveAttribute(
    'data-tab-surface',
    'account',
  );
  await expect(page.getByRole('button', { name: '关闭 个人资料' })).toBeVisible();
  await expect(page.getByText('用户 ID', { exact: true })).toBeVisible();
  await page.reload();
  await expect(page.getByText('成员身份 ID', { exact: true })).toBeVisible();
});

test('账户与组织页面使用靠右独立且可关闭的 tab', async ({ page }) => {
  await page.goto('/orders');
  await page.getByRole('button', { name: '账户与租户' }).click();
  await page.getByRole('link', { name: '组织与权限', exact: true }).click();
  const business = page.locator('[data-tab-surface="business"]').last();
  const account = page.locator('[data-tab-id="/org"]');
  await expect(account).toHaveAttribute('data-tab-surface', 'account');
  const [businessBox, accountBox] = await Promise.all([
    business.boundingBox(),
    account.boundingBox(),
  ]);
  expect(accountBox!.x).toBeGreaterThan(businessBox!.x + businessBox!.width);
  await account.getByRole('tab').click({ button: 'right' });
  await page.getByRole('menuitem', { name: '关闭其他', exact: true }).click();
  await expect(page.getByRole('tab')).toHaveCount(2);
  await expect(page.locator('[data-tab-surface="business"]')).toHaveCount(1);
  await page.getByRole('button', { name: '关闭 组织与权限' }).click();
  await expect(account).toHaveCount(0);
  await expect(page).toHaveURL('/');
});

test('tab 切换按钮与 tab 分离并只在当前分组内移动', async ({ page }) => {
  await page.goto('/orders');
  await page
    .getByRole('navigation', { name: '系统导航' })
    .getByRole('link', { name: '开发', exact: true })
    .click();
  await page.getByRole('button', { name: '账户与租户' }).click();
  await page.getByRole('link', { name: '个人资料与头像', exact: true }).click();
  await page.getByRole('button', { name: '账户与租户' }).click();
  await page.getByRole('link', { name: '组织与权限', exact: true }).click();

  const accountSwitcher = page.getByRole('group', { name: '账户 Tab 切换' });
  await expect(
    accountSwitcher.getByRole('button', { name: '当前分组的下一个 Tab' }),
  ).toBeDisabled();
  await accountSwitcher.getByRole('button', { name: '当前分组的上一个 Tab' }).click();
  await expect(page).toHaveURL('/account');

  await page.getByRole('tab', { name: /商品档案/ }).click();
  const businessSwitcher = page.getByRole('group', { name: '业务 Tab 切换' });
  await expect(businessSwitcher).toBeVisible();
  await businessSwitcher.getByRole('button', { name: '当前分组的上一个 Tab' }).click();
  await expect(page).toHaveURL('/orders');
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
