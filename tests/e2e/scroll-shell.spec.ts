import { test, expect } from '@playwright/test';

test('content scroll and overscroll leave the logo, navigation and tabs fixed', async ({
  page,
}) => {
  await page.setViewportSize({ width: 1440, height: 640 });
  await page.goto('/products/research');
  await expect(page.getByRole('tab', { selected: true })).toHaveText('选品与开发');
  const logo = page.getByRole('link', { name: 'Starcon 工作台' });
  const group = page.getByRole('button', { name: '商品中心', exact: true });
  const header = page.locator('header');
  const tabs = page.getByRole('tablist');
  const before = await Promise.all([
    logo.boundingBox(),
    group.boundingBox(),
    header.boundingBox(),
    tabs.boundingBox(),
  ]);
  const main = page.locator('main');
  await main.hover();
  await page.mouse.wheel(0, 700);
  await expect.poll(() => main.evaluate((el) => el.scrollTop)).toBeGreaterThan(0);
  const after = await Promise.all([
    logo.boundingBox(),
    group.boundingBox(),
    header.boundingBox(),
    tabs.boundingBox(),
  ]);
  expect(after.map((box) => box?.y)).toEqual(before.map((box) => box?.y));
  expect(await page.evaluate(() => window.scrollY)).toBe(0);
  await page.mouse.wheel(0, -3000);
  await expect.poll(() => main.evaluate((el) => el.scrollTop)).toBe(0);
  expect((await logo.boundingBox())?.y).toBe(before[0]?.y);
  expect(await page.evaluate(() => getComputedStyle(document.body).overscrollBehavior)).toBe(
    'none',
  );
  await main.hover();
  await page.mouse.wheel(0, 700);
  await expect.poll(() => main.evaluate((el) => el.scrollTop)).toBeGreaterThan(0);
  await page.getByRole('link', { name: 'Starcon 工作台' }).click();
  await expect(page).toHaveURL('/');
  await expect.poll(() => main.evaluate((el) => el.scrollTop)).toBe(0);
  await expect(page.getByRole('tab')).toHaveCount(2);
});

test('long side navigation scrolls independently of branding and content', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 640 });
  await page.goto('/orders');
  const nav = page.getByRole('navigation', { name: '功能导航' });
  for (const name of ['售后中心', 'CRM / 客服', '数据中心', '财务中心', '利润中心', '组织 / 权限'])
    await nav.getByRole('button', { name, exact: true }).click();
  const logo = page.getByRole('link', { name: 'Starcon 工作台' });
  const copyright = page.getByLabel('版权信息');
  const before = await Promise.all([logo.boundingBox(), copyright.boundingBox()]);
  const scroll = page.locator('aside > div').first();
  await scroll.hover();
  await page.mouse.wheel(0, 900);
  await expect.poll(() => scroll.evaluate((el) => el.scrollTop)).toBeGreaterThan(0);
  expect((await logo.boundingBox())?.y).toBe(before[0]?.y);
  expect((await copyright.boundingBox())?.y).toBe(before[1]?.y);
  expect(await page.locator('main').evaluate((el) => el.scrollTop)).toBe(0);
});

test('all six clocks share the same baseline within their row', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.goto('/');
  const clocks = page.getByRole('region', { name: '全球时间' }).locator('time');
  await expect(clocks).toHaveCount(6);
  const positions = await clocks.evaluateAll((els) =>
    els.map((el) => ({
      y: el.getBoundingClientRect().y - el.closest('[data-has-photo]')!.getBoundingClientRect().y,
      font: getComputedStyle(el).fontSize,
    })),
  );
  expect(new Set(positions.map((item) => item.y)).size).toBe(1);
  expect(new Set(positions.map((item) => item.font)).size).toBe(1);
});
