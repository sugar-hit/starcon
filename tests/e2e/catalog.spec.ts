import { test, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';

// Real local services, with a generated password kept outside source control.
const env = Object.fromEntries(
  readFileSync('deploy/.env.catalog.local', 'utf8')
    .trim()
    .split('\n')
    .map((line) => line.split('=')),
);

test('商品中心：SPU、公共属性、SKU 覆写、真实持久化与移动布局', async ({ page }) => {
  test.setTimeout(120_000);
  page.setDefaultTimeout(10_000);
  const suffix = Date.now().toString();
  const name = `集成验证手机 ${suffix}`;
  const created: string[] = [];
  await page.goto('/products');
  await page.getByLabel('密码', { exact: true }).fill(env.LOCAL_ADMIN_PASSWORD!);
  await page.getByRole('button', { name: '登录', exact: true }).click();
  await expect(page.getByRole('button', { name: '新建 SPU' })).toBeVisible();
  const request = async (path: string, method = 'GET', data?: unknown) => {
    const response = await page.request.fetch(`/api/catalog/${path}`, {
      method,
      headers: { Origin: 'http://localhost:3000' },
      ...(data === undefined ? {} : { data }),
    });
    expect(response.ok(), `${method} ${path}`).toBeTruthy();
    return response.json();
  };
  try {
    await page.getByRole('button', { name: '新建 SPU' }).click();
    await page.getByLabel('SPU 编码', { exact: true }).fill(`TEST-${suffix}`);
    await page.getByLabel('商品名称', { exact: true }).fill(name);
    await page.getByRole('button', { name: '保存', exact: true }).click();
    await expect(page.getByRole('heading', { name, exact: true })).toBeVisible();
    const products = await request('catalog/products');
    created.push(products.find((p: { name: string }) => p.name === name).id);
    const addAttribute = async (code: string, label: string, mode: string, value: string) => {
      await page.getByRole('button', { name: '添加属性' }).click();
      await page.getByLabel('属性编码', { exact: true }).fill(code);
      await page.getByLabel('属性名称', { exact: true }).fill(label);
      await page.getByLabel('取值方式', { exact: true }).selectOption(mode);
      await page.getByLabel('SPU 公共值', { exact: true }).fill(value);
      await page.getByRole('button', { name: '保存', exact: true }).click();
      await expect(page.getByRole('dialog')).toHaveCount(0);
    };
    await addAttribute('VIDEO', '视频规格', 'COMMON_LOCKED', '4K');
    await addAttribute('ACCESSORY', '配件', 'COMMON_DEFAULT', '标准套装');
    await page.getByRole('button', { name: '添加属性' }).click();
    await page.getByLabel('属性编码', { exact: true }).fill('COLOR');
    await page.getByLabel('属性名称', { exact: true }).fill('外壳颜色');
    await page.getByLabel('作为 SKU 规格轴').check();
    await page.getByLabel('可选值', { exact: true }).fill('黑色\n红色');
    await page.getByRole('button', { name: '保存', exact: true }).click();
    await expect(page.getByRole('dialog')).toHaveCount(0);
    await page.getByRole('button', { name: /SKU 完整属性/ }).click();
    for (const [code, color, override] of [
      ['BLACK', '黑色', false],
      ['RED', '红色', true],
    ] as const) {
      await page.getByRole('button', { name: '新建 SKU' }).click();
      await page.getByLabel('SKU 编码', { exact: true }).fill(`${code}-${suffix}`);
      await page.getByLabel('外壳颜色', { exact: true }).selectOption(color);
      if (override) {
        await page.getByLabel('配件取值方式').selectOption('SET');
        await page.getByLabel('配件', { exact: true }).fill('特别套装');
      }
      await page.getByRole('button', { name: '保存', exact: true }).click();
      await expect(page.getByRole('dialog')).toHaveCount(0);
    }
    await expect(page.getByRole('cell', { name: /4K/ })).toHaveCount(2);
    await expect(page.getByRole('cell', { name: /特别套装/ })).toBeVisible();
    await page.reload();
    await page.getByRole('button', { name, exact: true }).click();
    await page.getByRole('button', { name: /SKU 完整属性/ }).click();
    await expect(page.getByRole('cell', { name: /4K/ })).toHaveCount(2);
    await page.getByRole('button', { name: /属性配置与公共值/ }).click();
    const accessoryRow = page
      .getByRole('row')
      .filter({ has: page.getByText('配件', { exact: true }) });
    await accessoryRow.getByRole('button', { name: '编辑', exact: true }).click();
    await page.getByLabel('SPU 公共值', { exact: true }).fill('新版标准套装');
    await page.getByRole('button', { name: '保存', exact: true }).click();
    await expect(page.getByRole('dialog')).toHaveCount(0);
    await page.getByRole('button', { name: /SKU 完整属性/ }).click();
    await expect(page.getByRole('cell', { name: /新版标准套装/ })).toBeVisible();
    await expect(page.getByRole('cell', { name: /特别套装/ })).toBeVisible();
    await page.screenshot({ path: 'work/catalog-desktop.png', fullPage: true });
    for (const width of [390, 320]) {
      await page.setViewportSize({ width, height: 844 });
      expect(
        await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth),
      ).toBeTruthy();
    }
    await page.screenshot({ path: 'work/catalog-mobile.png', fullPage: true });
    await page.setViewportSize({ width: 1440, height: 1000 });
    for (const [kind, title] of [
      ['research', '选品与开发'],
      ['compliance', '合规与资质'],
      ['assets', '素材与翻译'],
    ]) {
      await page.goto(`/products/${kind}`);
      await page.getByRole('button', { name: '新建记录' }).click();
      const dialog = page.getByRole('dialog');
      await dialog.locator('input').first().fill(`测试记录 ${suffix}`);
      await dialog.getByRole('button', { name: '保存', exact: true }).click();
      await expect(dialog).toHaveCount(0);
      await expect(
        page.getByRole('cell', { name: `测试记录 ${suffix}`, exact: true }),
      ).toBeVisible();
      const rows = await request(`catalog/records/${kind}`);
      const row = rows.find((r: { title: string }) => r.title === `测试记录 ${suffix}`);
      await request(`catalog/records/${kind}/${row.id}?version=${row.version}`, 'DELETE');
      expect(title).toBeTruthy();
    }
    const forged = await page.request.post('/api/catalog/catalog/products', {
      headers: { Origin: 'https://untrusted.example' },
      data: {},
    });
    expect(forged.status()).toBe(403);
  } finally {
    for (const id of created) {
      let detail = await request(`catalog/products/${id}`);
      for (const sku of detail.skus) {
        await request(
          `catalog/products/${id}/skus/${sku.id}?version=${detail.product.version}`,
          'DELETE',
        );
        detail = await request(`catalog/products/${id}`);
      }
      await request(`catalog/products/${id}?version=${detail.product.version}`, 'DELETE');
    }
  }
});
