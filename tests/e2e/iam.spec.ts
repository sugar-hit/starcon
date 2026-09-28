import { test, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
const password = Object.fromEntries(
  readFileSync('deploy/.env.catalog.local', 'utf8')
    .trim()
    .split('\n')
    .map((line) => line.split('=')),
).LOCAL_ADMIN_PASSWORD!;

test('应用级登录、跨模块会话、独立组织管理和全局退出', async ({ page }) => {
  await page.goto('/orders');
  await expect(page).toHaveURL('/login?next=%2Forders');
  await expect(page.getByRole('heading', { name: '登录 Starcon' })).toBeVisible();
  await expect(page.getByRole('navigation', { name: '功能导航' })).toHaveCount(0);
  await page.getByLabel('账号', { exact: true }).fill('local-admin');
  await page.getByLabel('密码', { exact: true }).fill(password);
  await page.getByRole('button', { name: '登录', exact: true }).click();
  await expect(page).toHaveURL('/orders');
  await expect(page.getByRole('button', { name: '账户与租户' })).toBeVisible();
  const catalogCalls: string[] = [];
  page.on('request', (request) => {
    if (request.url().includes('/api/catalog/')) catalogCalls.push(request.url());
  });
  await page.goto('/org/members');
  await expect(page.getByRole('heading', { name: '用户与成员' }).first()).toBeVisible();
  await expect(page.getByText('local-admin', { exact: true })).toBeVisible();
  expect(catalogCalls).toEqual([]);
  await page.goto('/products/roles');
  await expect(page).toHaveURL('/org/roles');
  await page.reload();
  await expect(page.getByRole('button', { name: '账户与租户' })).toBeVisible();
  await page.getByRole('button', { name: '账户与租户' }).click();
  await expect(page.getByText('交互原型 · 未接入真实账户')).toHaveCount(0);
  await page.getByRole('button', { name: '退出', exact: true }).click();
  await expect(page).toHaveURL('/login');
  await page.goto('/org');
  await expect(page).toHaveURL('/login?next=%2Forg');
});

for (const width of [320, 768, 1440])
  test(`IAM 布局适配 ${width}`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await page.goto('/login');
    await page.getByLabel('账号', { exact: true }).fill('local-admin');
    await page.getByLabel('密码', { exact: true }).fill(password);
    await page.getByRole('button', { name: '登录', exact: true }).click();
    await expect(page.getByRole('button', { name: '账户与租户' })).toBeVisible();
    await page.goto('/org');
    await expect(page.getByRole('navigation', { name: '组织管理功能' })).toHaveCount(0);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(
      true,
    );
    expect(await page.locator('header').evaluate((el) => el.scrollWidth <= el.clientWidth)).toBe(
      true,
    );
    await page.getByRole('button', { name: '账户与租户' }).click();
    await expect(page.getByLabel('切换租户')).toBeInViewport();
    await page.screenshot({ path: `work/iam-${width}.png`, fullPage: true });
  });

test('切换租户清空编辑状态并同步其他标签页，多租户登录需要明确选择', async ({ page, context }) => {
  test.setTimeout(60000);
  const headers = { Origin: 'http://localhost:3000' };
  const post = async (path: string, data: unknown) => {
    const response = await page.request.post(`/api/iam/${path}`, { headers, data });
    expect(response.ok()).toBeTruthy();
    return response.json();
  };
  await post('auth/login', { username: 'local-admin', password, membershipId: null });
  const initial = await (await page.request.get('/api/iam/auth/session')).json();
  const name = `切换验证 ${Date.now()}`;
  const childId = await post('identity/children', {
    code: `SW${Date.now()}`,
    name,
    ownerUsername: 'local-admin',
    ownerPassword: null,
  });
  try {
    await page.goto('/products');
    await page.getByRole('button', { name: '新建 SPU' }).click();
    await page.getByLabel('商品名称', { exact: true }).fill('尚未保存');
    const other = await context.newPage();
    await other.goto('/org');
    await expect(other.getByRole('button', { name: '账户与租户' })).toBeVisible();
    await other.getByRole('button', { name: '账户与租户' }).click();
    await other.getByLabel('切换租户').click();
    await other.getByRole('option', { name, exact: true }).click();
    await other.getByRole('button', { name: '确认切换', exact: true }).click();
    await expect(other).toHaveURL('/');
    await expect(page.getByRole('button', { name: '账户与租户' })).toContainText(name);
    await expect(other.getByRole('button', { name: '账户与租户' })).toContainText(name);
    await expect(page.getByRole('dialog')).toHaveCount(0);
    await page.getByRole('button', { name: '账户与租户' }).click();
    await page.getByRole('button', { name: '退出', exact: true }).click();
    await expect(other.getByRole('heading', { name: '登录 Starcon' })).toBeVisible();
    await page.getByLabel('账号', { exact: true }).fill('local-admin');
    await page.getByLabel('密码', { exact: true }).fill(password);
    await page.getByRole('button', { name: '登录', exact: true }).click();
    await expect(page.getByRole('radio', { name: initial.tenantName })).toBeVisible();
    await page.getByRole('radio', { name: initial.tenantName }).click();
    await page.getByRole('button', { name: '进入工作空间' }).click();
    await expect(page.getByRole('button', { name: '账户与租户' })).toContainText(
      initial.tenantName,
    );
  } finally {
    await post('auth/login', {
      username: 'local-admin',
      password,
      membershipId: initial.membershipId,
    });
    const result = await page.request.put(`/api/iam/identity/children/${childId}`, {
      headers,
      data: { name, status: 'SUSPENDED' },
    });
    expect(result.ok()).toBeTruthy();
  }
});
