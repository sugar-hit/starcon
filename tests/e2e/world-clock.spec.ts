import { expect, test, type Page } from '@playwright/test';

const ROTATION_WAIT = 15_000;

function clocks(page: Page) {
  return page.getByRole('region', { name: '全球时间' });
}
function cards(page: Page) {
  return clocks(page).locator(':scope > div');
}
function rotatingCards(page: Page) {
  return clocks(page).locator(':scope > div[data-rotating="true"]');
}
function cardLabels(page: Page) {
  return cards(page).evaluateAll((els) => els.map((el) => el.getAttribute('aria-label')));
}
test.beforeEach(async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.goto('/');
  await expect(clocks(page).locator('time').first()).not.toHaveText('—:—');
});

test('every clock carries a globe drawn for its own city', async ({ page }) => {
  await expect(cards(page).locator('svg[role="img"]')).toHaveCount(6);

  const globes = await cards(page).evaluateAll((els) =>
    els.map((card) => {
      const svg = card.querySelector('svg[role="img"]')!;
      const land = svg.querySelector('g[clip-path] path')!;
      const grid = svg.querySelectorAll('g[clip-path] path')[1]!;
      const box = card.getBoundingClientRect();
      const g = svg.getBoundingClientRect();
      return {
        label: card.getAttribute('aria-label'),
        title: svg.getAttribute('aria-label'),
        landPoints: (land.getAttribute('d') || '').split('M').filter(Boolean).length,
        hasGrid: (grid.getAttribute('d') || '').length > 40,
        hasPin: !!svg.querySelector('g[opacity]'),
        onRight: g.left + g.width / 2 > box.left + box.width / 2,
        onTop: g.top + g.height / 2 < box.top + box.height / 2,
        size: Math.round(g.width),
      };
    }),
  );

  for (const globe of globes) {
    expect(globe.title, globe.label).toContain('在地球上的位置');
    expect(globe.landPoints, `${globe.label} 应有大陆`).toBeGreaterThan(2);
    expect(globe.hasGrid, `${globe.label} 应有经纬网`).toBe(true);
    expect(globe.hasPin, `${globe.label} 应有定位点`).toBe(true);
    expect(globe.onRight && globe.onTop, `${globe.label} 地球应在右上角`).toBe(true);
    expect(globe.size).toBeGreaterThanOrEqual(48);
  }
});

test('each globe faces a different part of the world', async ({ page }) => {
  const outlines = await cards(page).evaluateAll((els) =>
    els.map((el) => el.querySelector('g[clip-path] path')!.getAttribute('d')),
  );
  expect(new Set(outlines).size).toBe(6);
});

test('the five regional clocks rotate while the local one stays', async ({ page }) => {
  await expect(rotatingCards(page)).toHaveCount(5);
  const before = await cardLabels(page);
  expect(before).toHaveLength(6);
  expect(new Set(before).size).toBe(6);

  await expect
    .poll(async () => (await cardLabels(page)).slice(1), { timeout: ROTATION_WAIT })
    .not.toEqual(before.slice(1));

  const after = await cardLabels(page);
  expect(after[0]).toBe(before[0]);
  expect(new Set(after).size).toBe(6);
  for (const tooltip of await cards(page).evaluateAll((els) =>
    els.map((el) => el.getAttribute('title') || ''),
  )) {
    expect(tooltip === '' || /轮换/.test(tooltip)).toBe(true);
  }
});

test('each regional globe actually turns instead of jumping', async ({ page }) => {
  const card = cards(page).nth(1);
  const read = () => card.locator('svg[role="img"] g[clip-path] path').first().getAttribute('d');
  await expect(card).toHaveAttribute('data-switches', '1', { timeout: ROTATION_WAIT });

  // 换场后连续采样：补间应当逐帧改变轮廓，而不是一次跳完
  const seen = new Set<string>();
  for (let frame = 0; frame < 20; frame += 1) {
    seen.add((await read()) || '');
    await page.waitForTimeout(80);
  }
  expect(seen.size, '旋转过程应产生多帧不同的地球轮廓').toBeGreaterThan(6);
});

test('all six clocks stay distinct while rotating', async ({ page }) => {
  for (let sample = 0; sample < 14; sample += 1) {
    expect(new Set(await cardLabels(page)).size).toBe(6);
    await page.waitForTimeout(1000);
  }
});

test('rotation animates briefly and keeps the clock readable', async ({ page }) => {
  const rotating = rotatingCards(page).first();

  // 首屏不播入场动效
  expect(
    await rotating
      .locator('[data-clock-label]')
      .evaluate((el) => getComputedStyle(el).animationName),
  ).toBe('none');

  await expect(rotating).toHaveAttribute('data-switches', '1', { timeout: ROTATION_WAIT });
  await expect(rotating.locator('.clock-sweep')).toHaveCount(1);

  const seconds = Number.parseFloat(
    await rotating
      .locator('[data-clock-label]')
      .evaluate((el) => getComputedStyle(el).animationDuration),
  );
  expect(seconds).toBeGreaterThan(0.2);
  expect(seconds).toBeLessThanOrEqual(0.6);

  // 时间读数本身不参与动画，轮换时保持即时可读
  expect(await rotating.locator('time').evaluate((el) => getComputedStyle(el).animationName)).toBe(
    'none',
  );
});
