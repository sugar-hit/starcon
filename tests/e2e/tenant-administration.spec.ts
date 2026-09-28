import { test, expect, type Page } from '@playwright/test';
import { readFileSync } from 'node:fs';

// Real local services, with a generated password kept outside source control.
const env = Object.fromEntries(
  readFileSync('deploy/.env.catalog.local', 'utf8')
    .trim()
    .split('\n')
    .map((line) => line.split('=')),
);
const OWNER = 'local-admin';
const PASSWORD = env.LOCAL_ADMIN_PASSWORD!;

async function signIn(page: Page, username: string, password: string) {
  await page.getByLabel('账号', { exact: true }).fill(username);
  await page.getByLabel('密码', { exact: true }).fill(password);
  await page.getByRole('button', { name: '登录', exact: true }).click();
  await expect(page.getByRole('navigation', { name: '商品中心功能' })).toBeVisible();
}
async function signOut(page: Page) {
  await page.getByRole('button', { name: '退出', exact: true }).click();
  await expect(page.getByRole('button', { name: '登录', exact: true })).toBeVisible();
}

test('租户管理：部门、角色、成员、子租户的真实授权闭环', async ({ page }) => {
  test.setTimeout(180_000);
  page.setDefaultTimeout(10_000);
  const suffix = Date.now().toString();
  const department = `验证部门 ${suffix}`;
  const roleName = `选品只读 ${suffix}`;
  const adminRoleName = `运营管理 ${suffix}`;
  const memberName = `verify-${suffix}`;
  const memberPassword = `Verify-${suffix}-pw`;
  const childCode = `SUB${suffix}`;
  const childName = `验证子租户 ${suffix}`;
  const childOwner = `child-${suffix}`;
  const childOwnerPassword = `Child-${suffix}-pw`;
  const request = async (path: string, method = 'GET', data?: unknown) => {
    const response = await page.request.fetch(`/api/catalog/${path}`, {
      method,
      headers: { Origin: 'http://localhost:3000' },
      ...(data === undefined ? {} : { data }),
    });
    return { status: response.status(), body: response.ok() ? await response.json() : null };
  };
  const save = () => page.getByRole('button', { name: '保存', exact: true }).click();

  await page.goto('/products/departments');
  await signIn(page, OWNER, PASSWORD);

  // 部门：创建后同时出现在页面列表与授权 API。
  await page.getByRole('button', { name: '新增', exact: true }).click();
  await page.getByLabel('部门名称', { exact: true }).fill(department);
  await save();
  await expect(page.getByText(department, { exact: true })).toBeVisible();
  const departments = await request('identity/departments');
  expect(departments.status).toBe(200);
  expect(departments.body.map((d: { name: string }) => d.name)).toContain(department);

  // 角色：勾选的权限与状态真实保存。含身份管理权限的角色只能给全量范围，
  // 数据范围收窄只对纯商品权限开放。
  await page.goto('/products/roles');
  await page.getByRole('button', { name: '新增', exact: true }).click();
  await page.getByLabel('角色编码', { exact: true }).fill(`VIEWER${suffix}`);
  await page.getByLabel('角色名称', { exact: true }).fill(roleName);
  await page.getByRole('checkbox', { name: '商品档案·查看', exact: true }).check();
  await save();
  const roleRow = page.getByRole('listitem').filter({ hasText: roleName });
  await expect(roleRow).toContainText('1 项权限');
  await expect(roleRow).toContainText('启用');
  await page.getByRole('button', { name: '新增', exact: true }).click();
  await page.getByLabel('角色编码', { exact: true }).fill(`OPERATOR${suffix}`);
  await page.getByLabel('角色名称', { exact: true }).fill(adminRoleName);
  await page.getByRole('checkbox', { name: '成员管理·查看', exact: true }).check();
  await save();
  const roles = await request('identity/roles');
  const role = roles.body.find((r: { name: string }) => r.name === roleName);
  const adminRole = roles.body.find((r: { name: string }) => r.name === adminRoleName);
  expect(role.permissions).toEqual(expect.arrayContaining(['catalog.spu.read']));
  expect(adminRole.permissions).toEqual(expect.arrayContaining(['identity.member.read']));

  // 成员：部门归属与角色范围在同一次保存里生效。
  await page.goto('/products/members');
  await page.getByRole('button', { name: '新增', exact: true }).click();
  await page.getByLabel('账号', { exact: true }).fill(memberName);
  await page.getByLabel('新账号密码', { exact: true }).fill(memberPassword);
  await page.getByLabel('所属部门', { exact: true }).selectOption({ label: department });
  await page.getByLabel(roleName, { exact: true }).selectOption('DEPARTMENT');
  await save();
  const memberRow = page.getByRole('listitem').filter({ hasText: memberName });
  await expect(memberRow).toContainText(`${department} · ${roleName}（本部门）`);
  const members = await request('identity/members');
  const member = members.body.find((m: { username: string }) => m.username === memberName);
  expect(member.assignments).toEqual([{ roleId: role.id, scopeMode: 'DEPARTMENT' }]);

  // 子租户：创建即带首位所有者，父子关系不自动授予业务访问。
  await page.goto('/products/children');
  await page.getByRole('button', { name: '新增', exact: true }).click();
  await page.getByLabel('租户编码', { exact: true }).fill(childCode);
  await page.getByLabel('租户名称', { exact: true }).fill(childName);
  await page.getByLabel('账号', { exact: true }).fill(childOwner);
  await page.getByLabel('新账号密码', { exact: true }).fill(childOwnerPassword);
  await save();
  await expect(page.getByRole('listitem').filter({ hasText: childCode })).toContainText('启用');
  const children = await request('identity/children');
  expect(children.body.map((c: { code: string }) => c.code)).toContain(childCode);

  // 受限成员：本部门范围看不到管理页入口，也没有新增商品的权限。
  await signOut(page);
  await signIn(page, memberName, memberPassword);
  const workspaceNav = page.getByRole('navigation', { name: '商品中心功能' });
  await expect(workspaceNav.getByRole('link', { name: '成员管理', exact: true })).toHaveCount(0);
  await expect(workspaceNav.getByRole('link', { name: '角色配置', exact: true })).toHaveCount(0);
  await expect(workspaceNav.getByRole('link', { name: '商品档案', exact: true })).toBeVisible();
  await page.goto('/products');
  await expect(page.getByRole('button', { name: '新建 SPU', exact: true })).toHaveCount(0);
  const forbidden = await request('catalog/products', 'POST', {
    code: `DENY${suffix}`,
    name: '越权商品',
    description: '',
    brand: '',
    category: '',
    status: 'DRAFT',
    version: 0,
  });
  expect(forbidden.status).toBe(403);

  // 子租户所有者：管理能力在自己租户内成立，看不到父租户的商品。
  await signOut(page);
  await signIn(page, childOwner, childOwnerPassword);
  await expect(workspaceNav.getByRole('link', { name: '成员管理', exact: true })).toBeVisible();
  await page.goto('/products');
  await expect(page.getByRole('table')).toBeVisible();
  await expect(page.getByText('创建你的第一款商品', { exact: true })).toBeVisible();

  // 回收：子租户按设计无删除入口，其余按依赖顺序删除。
  await signOut(page);
  await signIn(page, OWNER, PASSWORD);
  await page.goto('/products/members');
  page.once('dialog', (dialog) => void dialog.accept());
  await page
    .getByRole('listitem')
    .filter({ hasText: memberName })
    .getByRole('button', { name: '移除' })
    .click();
  await expect(page.getByText(memberName, { exact: true })).toHaveCount(0);
  await page.goto('/products/roles');
  for (const name of [roleName, adminRoleName]) {
    page.once('dialog', (dialog) => void dialog.accept());
    await page
      .getByRole('listitem')
      .filter({ hasText: name })
      .getByRole('button', { name: '删除' })
      .click();
    await expect(page.getByText(name, { exact: true })).toHaveCount(0);
  }
  await page.goto('/products/departments');
  page.once('dialog', (dialog) => void dialog.accept());
  await page
    .getByRole('listitem')
    .filter({ hasText: department })
    .getByRole('button', { name: '删除' })
    .click();
  await expect(page.getByText(department, { exact: true })).toHaveCount(0);
});
