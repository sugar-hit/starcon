import { test, expect } from '@playwright/test';

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

test('account prototype validates, cancels, previews permissions and resets on reload', async ({
  page,
}) => {
  await page.goto('/products');
  await page.getByRole('button', { name: '账户：体验用户' }).click();
  await expect(page.getByText('交互原型 · 未接入真实账户')).toBeVisible();
  await expect(page.getByRole('region', { name: '租户信息' })).toContainText(
    '普通用户 · 租户信息只读',
  );
  await expect(page.getByRole('button', { name: '修改租户信息' })).toHaveCount(0);
  await page.getByRole('button', { name: '修改个人信息' }).click();
  const dialog = page.getByRole('dialog');
  await dialog.getByLabel('用户姓名').fill('   ');
  await dialog.getByRole('button', { name: '保存原型修改' }).click();
  await expect(dialog.getByText('请输入名称。')).toBeVisible();
  await dialog.getByLabel('用户姓名').fill('林晓');
  await dialog.getByLabel('个人邮箱').fill('lin@example.com');
  await dialog.getByRole('button', { name: '保存原型修改' }).click();
  await expect(dialog).toHaveCount(0);
  await expect(page.getByRole('button', { name: '账户：林晓' })).toBeFocused();
  await page.getByRole('button', { name: '账户：林晓' }).click();
  await expect(page.getByText('lin@example.com')).toBeVisible();
  await page.getByRole('radio', { name: '超级管理员', exact: true }).click();
  await page.getByRole('button', { name: '修改租户信息' }).click();
  await dialog.getByLabel('租户名称', { exact: true }).fill('测试企业');
  await dialog.getByLabel('租户联系邮箱').fill('team@example.com');
  await dialog.getByRole('button', { name: '保存原型修改' }).click();
  await page.getByRole('button', { name: '账户：林晓' }).click();
  await expect(page.getByRole('region', { name: '租户信息' })).toContainText('测试企业');
  await page.getByRole('radio', { name: '普通用户', exact: true }).click();
  await expect(page.getByRole('button', { name: '修改租户信息' })).toHaveCount(0);
  await page.getByRole('button', { name: '修改个人信息' }).click();
  await dialog.getByLabel('用户姓名').fill('未保存');
  await dialog.getByRole('button', { name: '取消', exact: true }).click();
  await page.getByRole('button', { name: '账户：林晓' }).click();
  await page.getByRole('button', { name: '修改个人信息' }).click();
  await expect(dialog.getByLabel('用户姓名')).toHaveValue('林晓');
  await page.keyboard.press('Escape');
  await expect(page).toHaveURL('/products');
  await expect(page.getByRole('tab')).toHaveCount(2);
  await page.getByRole('button', { name: '搜索功能', exact: true }).click();
  await page.getByRole('combobox', { name: '搜索功能名称' }).fill('全部订单');
  await page.getByRole('option', { name: /全部订单/ }).click();
  await expect(page).toHaveURL('/orders');
  await expect(page.getByRole('button', { name: '账户：林晓' })).toBeVisible();
  await page.reload();
  await page.getByRole('button', { name: '账户：体验用户' }).click();
  await expect(page.getByRole('region', { name: '租户信息' })).toContainText('尚未连接租户');
  await expect(page.getByRole('button', { name: '修改租户信息' })).toHaveCount(0);
});

for (const width of [320, 390, 768, 1024, 1920]) {
  test(`account and permanent tabs fit ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 800 });
    await page.goto('/');
    const account = page.getByRole('button', { name: '账户：体验用户' });
    await expect(account).toBeInViewport();
    await expect(page.getByText('Starcon 演示空间')).toHaveCount(0);
    const header = page.locator('header');
    expect(await header.evaluate((el) => el.scrollWidth <= el.clientWidth)).toBe(true);
    await account.click();
    await page.getByRole('button', { name: '修改个人信息' }).click();
    const dialog = page.getByRole('dialog');
    expect(await dialog.evaluate((el) => el.scrollWidth <= el.clientWidth)).toBe(true);
    await expect(dialog.getByRole('button', { name: '保存原型修改' })).toBeInViewport();
    await page.keyboard.press('Escape');
    await expect(account).toBeFocused();
  });
}
