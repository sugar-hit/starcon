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

test('every clock carries a globe marker pinned to the card corner', async ({ page }) => {
  const markers = cards(page).locator('svg circle');
  await expect(markers).toHaveCount(6);

  const positions = await cards(page).evaluateAll((els) =>
    els.map((card) => {
      const box = card.getBoundingClientRect();
      const globe = card.querySelector('svg')!.getBoundingClientRect();
      return {
        onRight: globe.left + globe.width / 2 > box.left + box.width / 2,
        onTop: globe.top + globe.height / 2 < box.top + box.height / 2,
        small: globe.width <= 18 && globe.height <= 18,
      };
    }),
  );
  expect(positions.every((item) => item.onRight && item.onTop && item.small)).toBe(true);
});

test('the last two cities rotate to other global cities on their own', async ({ page }) => {
  const before = await cardLabels(page);
  expect(before).toHaveLength(6);
  expect(before[4]).not.toBe(before[5]);

  await expect
    .poll(async () => (await cardLabels(page)).slice(4), { timeout: ROTATION_WAIT })
    .not.toEqual(before.slice(4));

  const after = await cardLabels(page);
  expect(after.slice(0, 4)).toEqual(before.slice(0, 4));
  expect(new Set(after).size).toBe(6);
});

test('all six clocks stay distinct while rotating', async ({ page }) => {
  for (let sample = 0; sample < 14; sample += 1) {
    expect(new Set(await cardLabels(page)).size).toBe(6);
    await page.waitForTimeout(1000);
  }
});

test('rotation animates briefly and keeps the clock readable', async ({ page }) => {
  await expect(rotatingCards(page)).toHaveCount(2);
  const rotating = rotatingCards(page).first();

  // 首屏不播入场动效
  expect(
    await rotating
      .locator('[data-clock-label]')
      .evaluate((el) => getComputedStyle(el).animationName),
  ).toBe('none');

  await expect(rotating).toHaveAttribute('data-switches', '1', { timeout: ROTATION_WAIT });

  const label = rotating.locator('[data-clock-label]');
  const seconds = Number.parseFloat(
    await label.evaluate((el) => getComputedStyle(el).animationDuration),
  );
  expect(seconds).toBeGreaterThan(0.2);
  expect(seconds).toBeLessThanOrEqual(0.6);

  // 时间读数本身不参与动画，轮换时保持即时可读
  expect(await rotating.locator('time').evaluate((el) => getComputedStyle(el).animationName)).toBe(
    'none',
  );
});
