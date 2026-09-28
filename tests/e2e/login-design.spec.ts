import { test, expect } from '@playwright/test';

for (const width of [320, 390, 768, 1440, 1920]) {
  test(`沉浸登录布局与响应式图片 ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await page.goto('/login');
    await expect(page.getByRole('heading', { name: '登录 Starcon' })).toBeVisible();
    const slogan = page.getByRole('region', { name: '品牌宣言' });
    const form = page.getByRole('region', { name: '登录表单' });
    await expect(slogan).toContainText('让跨境生意，');
    const left = await slogan.boundingBox();
    const right = await form.boundingBox();
    expect(left).not.toBeNull();
    expect(right).not.toBeNull();
    expect((await slogan.getByRole('heading').boundingBox())!.height).toBeLessThan(180);
    if (width >= 768) expect(left!.x + left!.width).toBeLessThan(right!.x);
    else expect(left!.y + left!.height).toBeLessThan(right!.y);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(
      true,
    );
    const background = page.locator('picture img');
    await expect
      .poll(() =>
        background.evaluate((img: HTMLImageElement) => img.complete && img.naturalWidth > 0),
      )
      .toBe(true);
    const url = await background.evaluate((img: HTMLImageElement) => img.currentSrc);
    expect(new URL(url).origin).toBe('http://localhost:3000');
    if (width < 768) expect(url).toContain('harbor-mobile');
    const response = await page.request.get(url);
    expect((await response.body()).length).toBeLessThan(width < 768 ? 25000 : 100000);
    await page.screenshot({ path: `work/login-design-${width}.png`, fullPage: true });
    await page.getByRole('button', { name: '登录', exact: true }).scrollIntoViewIfNeeded();
    await expect(page.getByRole('button', { name: '登录', exact: true })).toBeInViewport();
  });
}

test('背景图片未返回时表单仍可使用，图片失败仍保留文案和登录入口', async ({ page }) => {
  let release!: () => void;
  const pending = new Promise<void>((resolve) => {
    release = resolve;
  });
  await page.route('**/harbor-*.webp', async (route) => {
    await pending;
    await route.abort();
  });
  await page.goto('/login', { waitUntil: 'domcontentloaded' });
  await expect(page.getByRole('heading', { name: '登录 Starcon' })).toBeVisible();
  await page.getByLabel('账号', { exact: true }).fill('background-independent');
  await expect(page.getByLabel('账号', { exact: true })).toHaveValue('background-independent');
  await expect(page.getByRole('button', { name: '登录', exact: true })).toBeEnabled();
  release();
  await expect(page.getByRole('region', { name: '品牌宣言' })).toBeVisible();
  await expect(page.getByRole('button', { name: '登录', exact: true })).toBeVisible();
});
