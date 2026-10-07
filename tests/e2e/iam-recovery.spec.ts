import { test, expect } from '@playwright/test';

for (const outcome of ['lost', 'rejected', 'expired', 'success'] as const) {
  test(`租户切换恢复：${outcome}`, async ({ page }) => {
    const access = (target: boolean) => ({
      userId: 'user-1',
      tenantId: target ? 'tenant-2' : 'tenant-1',
      tenantName: target ? '目标企业' : '原企业',
      membershipId: target ? 'member-2' : 'member-1',
      departmentId: null,
      permissions: [],
      grants: [],
    });
    let switched = false;
    let unavailable = true;
    let switches = 0;
    await page.route('**/api/iam/auth/**', async (route) => {
      const path = new URL(route.request().url()).pathname;
      if (path.endsWith('/switch')) {
        switches++;
        if (outcome === 'rejected') {
          await route.fulfill({ status: 403, json: { message: '没有目标租户的授权' } });
        } else if (outcome === 'success') {
          switched = true;
          unavailable = false;
          await route.fulfill({ status: 204 });
        } else {
          switched = true;
          await route.abort('failed');
        }
      } else if (path.endsWith('/session')) {
        if (switched && outcome === 'expired') {
          await route.fulfill({ status: 401, body: 'expired' });
        } else if (switched && unavailable) {
          await route.fulfill({ status: 503, json: { message: '暂不可用' } });
        } else {
          await route.fulfill({ json: access(switched) });
        }
      } else if (path.endsWith('/memberships')) {
        await route.fulfill({
          json: [
            { id: 'member-1', tenantId: 'tenant-1', name: '原企业' },
            { id: 'member-2', tenantId: 'tenant-2', name: '目标企业' },
          ],
        });
      }
    });
    await page.goto('/account');
    await page.getByRole('button', { name: '账户与租户' }).click();
    await page.getByLabel('切换租户', { exact: true }).click();
    await page.getByRole('option', { name: '目标企业', exact: true }).click();
    await page.getByRole('button', { name: '取消', exact: true }).click();
    expect(switches).toBe(0);
    await expect(page.getByRole('button', { name: '账户与租户' })).toContainText('原企业');
    await page.getByRole('button', { name: '账户与租户' }).click();
    await page.getByLabel('切换租户', { exact: true }).click();
    await page.getByRole('option', { name: '目标企业', exact: true }).click();
    await page.getByRole('button', { name: '确认切换', exact: true }).click();
    if (outcome === 'lost') {
      await expect(page.getByRole('alert').filter({ hasText: '业务操作已暂停' })).toBeVisible();
      await expect(page.getByRole('button', { name: '账户与租户' })).toHaveCount(0);
      await page.screenshot({ path: 'work/iam-workspace-recovery.png', fullPage: true });
      unavailable = false;
      await page.getByRole('button', { name: '重试核验' }).click();
      await expect(page.getByRole('button', { name: '账户与租户' })).toContainText('目标企业');
      await expect(page).toHaveURL('/');
    } else if (outcome === 'rejected') {
      await expect(page.getByRole('button', { name: '账户与租户' })).toContainText('原企业');
      await expect(page.getByRole('alert').filter({ hasText: '没有目标租户的授权' })).toBeVisible();
    } else if (outcome === 'success') {
      await expect(page.getByRole('button', { name: '账户与租户' })).toContainText('目标企业');
      await expect(page).toHaveURL('/');
    } else {
      await expect(page.getByRole('heading', { name: '登录 Starcon' })).toBeVisible();
    }
    expect(switches).toBe(1);
  });
}
