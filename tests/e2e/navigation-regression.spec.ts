import { test, expect } from './support/authenticated';

test('侧栏二级任务不在页面正文重复为 tab 或快捷导航', async ({ page }) => {
  await page.goto('/products');
  await expect(page.getByRole('navigation', { name: '商品中心功能' })).toHaveCount(0);
  await expect(page.getByRole('main').getByRole('link', { name: '选品与开发' })).toHaveCount(0);
  await page.goto('/org');
  await expect(page.getByRole('navigation', { name: '组织管理功能' })).toHaveCount(0);
  await page.goto('/orders/review');
  await expect(page.getByRole('heading', { name: '相关任务' })).toHaveCount(0);
});

async function openProductTasks(page: import('@playwright/test').Page) {
  await page.goto('/products');
  await expect(page.getByRole('tab', { selected: true })).toHaveText('商品档案');
  const nav = page.getByRole('navigation', { name: '功能导航' });
  await nav.getByRole('link', { name: '选品与开发', exact: true }).click();
  await expect(page).toHaveURL('/products/research');
  await nav.getByRole('link', { name: '合规与资质', exact: true }).click();
  await expect(page).toHaveURL('/products/compliance');
}
for (const action of ['关闭当前', '关闭其他', '关闭左侧', '关闭右侧', '关闭全部']) {
  test(`${action} keeps nested route and tab selection consistent`, async ({ page }) => {
    await openProductTasks(page);
    await page.getByRole('tab', { name: '选品与开发', exact: true }).click({ button: 'right' });
    await page.getByRole('menuitem', { name: action, exact: true }).click();
    if (action === '关闭全部') {
      await expect(page).toHaveURL('/');
      await expect(page.getByRole('tab')).toHaveCount(1);
    } else if (action === '关闭当前') {
      await expect(page).toHaveURL('/products/compliance');
      await expect(page.getByRole('tab')).toHaveCount(3);
    } else {
      await expect(page).toHaveURL('/products/research');
      await expect(page.getByRole('tab', { selected: true })).toHaveText('选品与开发');
      await expect(page.getByRole('tab')).toHaveCount(action === '关闭其他' ? 2 : 3);
    }
  });
}
test('group switcher, keyboard menu and logo preserve the workspace', async ({ page }) => {
  await openProductTasks(page);
  await page.getByRole('button', { name: '当前分组的上一个 Tab' }).click();
  await expect(page).toHaveURL('/products/research');
  await page.getByRole('button', { name: '当前分组的下一个 Tab' }).click();
  await expect(page).toHaveURL('/products/compliance');
  await page.getByRole('tab', { name: '合规与资质', exact: true }).press('Home');
  await expect(page).toHaveURL('/');
  await page.getByRole('tab', { name: '商品档案', exact: true }).press('Shift+F10');
  await expect(page.getByRole('menuitem', { name: '关闭当前', exact: true })).toBeFocused();
  await page.keyboard.press('Escape');
  await page.getByRole('link', { name: 'Starcon 工作台' }).click();
  await expect(page).toHaveURL('/');
  await expect(page.getByRole('tab')).toHaveCount(4);
  await expect(page.getByRole('tab', { selected: true })).toHaveText('工作台');
  await page.reload();
  await expect(page.getByRole('tab')).toHaveCount(4);
  await expect(page.getByRole('tab', { selected: true })).toHaveText('工作台');
});
test('legacy tab metadata upgrades and invalid entries are discarded', async ({ page }) => {
  await page.addInitScript(() =>
    localStorage.setItem(
      'starcon.workspace.tabs.v2',
      JSON.stringify({
        tabs: [
          { id: '/products', href: '/products', label: '商品中心', systemId: 'dev', order: 0 },
          { id: 'duplicate', href: '/products', label: '重复', order: 3 },
          { id: '/copilot', href: '/copilot', label: 'AI Copilot', order: 4 },
          { id: '/made-up', href: '/made-up', label: '无效', order: 5 },
        ],
      }),
    ),
  );
  await page.goto('/');
  await expect(page.getByRole('tab')).toHaveCount(2);
  await expect(page.getByRole('tab')).toHaveText(['工作台', '商品档案']);
  await expect(page.getByRole('tab', { selected: true })).toHaveText('工作台');
});

test('未认证页面在 JavaScript 执行前不暴露业务工作区', async ({ browser }) => {
  const context = await browser.newContext({ javaScriptEnabled: false });
  const page = await context.newPage();
  await page.goto('http://localhost:3000/products/research');
  await expect(page.getByRole('tab')).toHaveCount(0);
  await expect(page.getByRole('status')).toHaveText('正在恢复工作空间…');
  await context.close();
});

test('new tab and destination commit together and survive refresh', async ({ page }) => {
  await page.goto('/products');
  await expect(page.getByRole('tab', { selected: true })).toHaveText('商品档案');
  let release!: () => void;
  const gate = new Promise<void>((resolve) => {
    release = resolve;
  });
  await page.route('**/products/research?**', async (route) => {
    await gate;
    await route.continue();
  });
  try {
    await page
      .getByRole('navigation', { name: '功能导航' })
      .getByRole('link', { name: '选品与开发', exact: true })
      .click();
    await expect(page.getByRole('tab', { name: '选品与开发', exact: true })).toHaveCount(0);
    await expect(page.getByRole('tab', { selected: true })).toHaveText('商品档案');
  } finally {
    release();
  }
  await expect(page).toHaveURL('/products/research');
  await expect(page.getByRole('tab', { selected: true })).toHaveText('选品与开发');
  await page.reload();
  await expect(page.getByRole('tab')).toHaveText(['工作台', '商品档案', '选品与开发']);
  await page.getByRole('button', { name: '关闭 选品与开发', exact: true }).click();
  await expect(page).toHaveURL('/');
  await expect(page.getByRole('tab')).toHaveText(['工作台', '商品档案']);
});
