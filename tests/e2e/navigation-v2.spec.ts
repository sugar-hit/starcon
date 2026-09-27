import { test, expect } from '@playwright/test';
import { SYSTEMS } from '../../packages/ui/src/lib/nav-config';

test('all 55 task destinations render and parent buttons never navigate', async ({ page }) => {
  test.setTimeout(120000);
  await page.goto('/');
  for (const system of SYSTEMS) {
    await page
      .getByRole('navigation', { name: '系统导航' })
      .getByRole('link', { name: system.label, exact: true })
      .click();
    await expect(page).toHaveURL(system.defaultHref);
    const nav = page.getByRole('navigation', { name: '功能导航' });
    for (const group of system.items) {
      const parent = nav.getByRole('button', { name: group.label, exact: true });
      const before = page.url();
      const count = await page.getByRole('tab').count();
      if ((await parent.getAttribute('aria-expanded')) === 'true') await parent.click();
      await expect(parent).toHaveAttribute('aria-expanded', 'false');
      await parent.click();
      await expect(page).toHaveURL(before);
      await expect(page.getByRole('tab')).toHaveCount(count);
      for (const child of group.children ?? []) {
        await nav.getByRole('link', { name: child.label, exact: true }).click();
        await expect(page).toHaveURL(child.href);
        await expect(page.getByRole('heading', { level: 1 })).toHaveText(child.label);
        await expect(page.getByRole('tab', { selected: true })).toHaveText(child.label);
        await expect(page.locator('main')).not.toContainText('这里没有页面');
        await nav.getByRole('link', { name: child.label, exact: true }).click();
        await expect(page.getByRole('tab', { name: child.label, exact: true })).toHaveCount(1);
      }
    }
  }
  await expect(page.getByRole('tab')).toHaveCount(56);
  await expect(page.getByRole('tab', { name: '工作台', exact: true })).toBeInViewport();
  await page.getByRole('tab', { name: '工作台', exact: true }).click();
  await expect(page).toHaveURL('/');
  await page.getByRole('button', { name: '跳到最右 Tab' }).click();
  await expect(page).toHaveURL('/inventory/aging');
  await page.reload();
  await expect(page.getByRole('tab')).toHaveCount(56);
  await expect(page.getByRole('tab', { selected: true })).toHaveText('库龄与预警');
});

for (const width of [320, 390, 768, 1440, 1920]) {
  test(`workbench preserves tabs, clocks and readable Copilot at ${width}`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await page.goto('/products/research');
    await expect(page.getByRole('tab', { selected: true })).toHaveText('选品与开发');
    await page.getByRole('link', { name: 'Starcon 工作台' }).filter({ visible: true }).click();
    await expect(page).toHaveURL('/');
    await expect(page.getByRole('tab')).toHaveCount(2);
    await expect(page.getByRole('tab', { selected: true })).toHaveText('工作台');
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('工作台');
    await expect(page.getByLabel('北京时间')).toHaveText(/\d{2}:\d{2}/);
    await expect(page.getByRole('region', { name: '全球时间' }).locator('time')).toHaveCount(6);
    await expect(page.getByText('演示环境', { exact: true })).toHaveCount(0);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(
      true,
    );
    if (width >= 768)
      await expect(page.getByLabel('版权信息')).toContainText('湃宁科技（深圳）有限责任公司');
    await page.getByRole('button', { name: '选品与开发', exact: true }).click();
    await expect(page).toHaveURL('/products/research');
    await page.getByRole('button', { name: 'AI Copilot', exact: true }).click();
    const dialog = page.getByRole('dialog', { name: 'AI Copilot' });
    await expect(dialog).toBeVisible();
    const box = await dialog.boundingBox();
    expect(Math.round(box!.width)).toBe(width < 640 ? width : Math.min(960, width - 48));
    expect(await dialog.evaluate((el) => el.scrollWidth <= el.clientWidth)).toBe(true);
    await expect(page).toHaveURL('/products/research');
    await expect(page.getByRole('tab', { includeHidden: true })).toHaveCount(2);
    const scrim = page.locator('.copilot-backdrop');
    expect(await scrim.evaluate((el) => getComputedStyle(el).backdropFilter)).toBe('none');
    expect(await scrim.evaluate((el) => getComputedStyle(el).backgroundColor)).toContain('0.14');
    await page.keyboard.press('Escape');
    await expect(dialog).toHaveCount(0);
    await page.getByRole('tab', { name: '选品与开发', exact: true }).click({ button: 'right' });
    await page.getByRole('menuitem', { name: '关闭全部', exact: true }).click();
    await expect(page).toHaveURL('/');
    await expect(page.getByRole('tab')).toHaveCount(1);
    if (width < 768) {
      await page.getByRole('button', { name: '菜单', exact: true }).click();
      const nav = page.getByRole('dialog', { name: '功能导航' });
      await nav.getByRole('button', { name: '商品中心', exact: true }).click();
      await expect(page).toHaveURL('/');
      await nav.getByRole('link', { name: '合规与资质', exact: true }).click();
      await expect(nav).toHaveCount(0);
      await expect(page).toHaveURL('/products/compliance');
    }
  });
}

test('direct nested route, history, closing and restoring secondary tabs', async ({ page }) => {
  await page.goto('/inventory/replenishment');
  await expect(page.getByRole('tab', { selected: true })).toHaveText('补货建议');
  await page
    .getByRole('navigation', { name: '功能导航' })
    .getByRole('link', { name: '库龄与预警', exact: true })
    .click();
  await expect(page.getByRole('tab')).toHaveCount(3);
  await expect(page).toHaveURL('/inventory/aging');
  await page.goBack();
  await expect(page.getByRole('tab', { selected: true })).toHaveText('补货建议');
  await page.getByRole('button', { name: '关闭 补货建议', exact: true }).click();
  await expect(page).toHaveURL('/');
  await expect(page.getByRole('tab')).toHaveCount(2);
  await page.getByRole('button', { name: '库龄与预警', exact: true }).click();
  await expect(page).toHaveURL('/inventory/aging');
  await page
    .getByRole('navigation', { name: '功能导航' })
    .getByRole('link', { name: '补货建议', exact: true })
    .click();
  await expect(page.getByRole('tab')).toHaveCount(3);
  await expect(page.getByRole('heading', { level: 1 })).toHaveText('补货建议');
});

test('unknown child URLs do not create phantom tabs', async ({ page }) => {
  await page.goto('/inventory/not-a-task');
  await expect(page.getByText('这里没有页面')).toBeVisible();
  await expect(page.getByRole('tab')).toHaveCount(1);
});
