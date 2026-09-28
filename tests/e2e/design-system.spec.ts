import { test, expect } from './support/authenticated';

test('view form validates, cancels and submits without a backend mutation', async ({ page }) => {
  await page.goto('/styleguide');
  await page.getByRole('button', { name: '新建视图', exact: true }).click();
  const dialog = page.getByRole('dialog');
  await dialog.getByRole('button', { name: '创建视图', exact: true }).click();
  await expect(dialog.getByText('请输入视图名称。')).toBeVisible();
  await expect(dialog.getByLabel('视图名称')).toBeFocused();
  await dialog.getByRole('button', { name: '取消', exact: true }).click();
  await expect(dialog).toHaveCount(0);
  await page.getByRole('button', { name: '新建视图', exact: true }).click();
  await dialog.getByLabel('视图名称').fill('Amazon 待发货');
  await dialog.getByRole('button', { name: '创建视图', exact: true }).click();
  await expect(dialog).toHaveCount(0);
  await expect(page.getByText('已创建示例视图「Amazon 待发货」')).toBeVisible();
});

test('density supports keyboard selection', async ({ page }) => {
  await page.goto('/styleguide');
  const group = page.getByRole('radiogroup', { name: '表格密度' });
  await group.getByRole('radio', { name: '舒适' }).focus();
  await page.keyboard.press('ArrowRight');
  await expect(group.getByRole('radio', { name: '紧凑' })).toBeChecked();
  await expect(page.getByText('当前密度：紧凑')).toBeVisible();
  await page.keyboard.press('Home');
  await expect(group.getByRole('radio', { name: '舒适' })).toBeChecked();
});

test('order detail opens and closes without losing route', async ({ page }) => {
  await page.goto('/orders');
  await page.getByRole('button', { name: '打开订单详情抽屉' }).click();
  await expect(page.getByRole('dialog')).toContainText('订单详情');
  await expect(page.getByRole('dialog')).toContainText('字段结构示例');
  await page.keyboard.press('Escape');
  await expect(page.getByRole('dialog')).toHaveCount(0);
  await expect(page).toHaveURL('/orders');
});

test('command search navigates to a matching feature', async ({ page }) => {
  await page.goto('/styleguide');
  await page.getByRole('button', { name: '搜索功能', exact: true }).click();
  await page.getByLabel('搜索功能名称').fill('订单');
  await page
    .getByRole('dialog')
    .getByRole('option', { name: /全部订单/ })
    .click();
  await expect(page).toHaveURL(/\/orders$/);
  await expect(page.getByRole('heading', { name: '全部订单', exact: true })).toBeVisible();
});

for (const width of [320, 390, 768, 1280]) {
  test(`layouts fit ${width}px and keep table locally scrollable`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    for (const route of ['/styleguide', '/', '/orders']) {
      await page.goto(route);
      await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
      expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(
        width,
      );
    }
    if (width < 768) {
      await page.goto('/orders/review');
      const region = page.getByRole('region', { name: '订单审核数据表' });
      expect(await region.evaluate((e) => e.scrollWidth > e.clientWidth)).toBe(true);
      await page.getByRole('button', { name: '菜单', exact: true }).click();
      await expect(page.getByRole('dialog')).toBeVisible();
      await page.getByRole('dialog').getByRole('link', { name: '仓储', exact: true }).click();
      await expect(page).toHaveURL(/\/warehouse$/);
      await expect(page.getByRole('dialog')).toHaveCount(0);
    }
  });
}
